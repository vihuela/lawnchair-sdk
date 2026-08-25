package app.lawnchair.sdk

import android.content.Context
import android.content.Intent
import android.os.Process
import android.view.View
import app.lawnchair.LawnchairLauncher
import app.lawnchair.sdk.api.DesktopItemRowAnchor
import app.lawnchair.sdk.api.DesktopItemSpec
import app.lawnchair.sdk.api.DesktopRevealAnimation
import app.lawnchair.sdk.api.MinusOneAnimation
import app.lawnchair.sdk.runtime.DesktopGrid
import app.lawnchair.sdk.runtime.DesktopItemLocation
import app.lawnchair.sdk.runtime.DesktopItemOpenStatus
import app.lawnchair.sdk.runtime.DesktopItemRevealPort
import app.lawnchair.sdk.runtime.DesktopItemRevealStatus
import app.lawnchair.sdk.runtime.DesktopItemsReconciler
import app.lawnchair.sdk.runtime.DesktopWorkspaceMutation
import app.lawnchair.sdk.runtime.DesktopWorkspacePort
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherModel
import com.android.launcher3.LauncherSettings
import com.android.launcher3.LauncherState
import com.android.launcher3.WorkspaceLayoutManager
import com.android.launcher3.icons.BaseIconFactory
import com.android.launcher3.icons.LauncherIcons
import com.android.launcher3.model.AllAppsList
import com.android.launcher3.model.BgDataModel
import com.android.launcher3.model.ModelDbController
import com.android.launcher3.model.ModelTaskController
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.util.Executors
import com.android.launcher3.util.IntArray
import java.util.function.Predicate
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Android 15 LauncherModel/DB implementation of the launcher-independent workspace port. */
internal class LauncherWorkspaceAdapter(context: Context) :
    DesktopWorkspacePort,
    DesktopItemRevealPort {
    private val appContext = context.applicationContext
    private val model: LauncherModel
        get() = LauncherAppState.getInstance(appContext).model

    override suspend fun awaitReady(): Boolean {
        if (model.isModelLoaded) return true

        // loadAsync's callback can run before a LoaderTask that was already posted has started.
        // Request a load, then observe LauncherModel's authoritative ready state instead.
        model.loadAsync {}
        return withTimeoutOrNull(MODEL_READY_TIMEOUT_MILLIS) {
            while (!model.isModelLoaded) {
                delay(MODEL_READY_POLL_INTERVAL_MILLIS)
            }
            true
        } ?: false
    }

    override suspend fun reconcile(
        specs: List<DesktopItemSpec>,
        removedIds: Set<String>,
    ): DesktopWorkspaceMutation = suspendCancellableCoroutine { continuation ->
        model.enqueueModelUpdateTask(
            ReconcileDesktopItemsTask(specs, removedIds) { result ->
                if (continuation.isActive) continuation.resumeWith(result)
            },
        )
    }

    override suspend fun remove(itemId: String): DesktopWorkspaceMutation = suspendCancellableCoroutine { continuation ->
        Executors.MODEL_EXECUTOR.execute {
            if (model.isModelLoaded) {
                model.enqueueModelUpdateTask(
                    RemoveDesktopItemTask(itemId) { result ->
                        if (continuation.isActive) continuation.resumeWith(result)
                    },
                )
            } else {
                runCatching {
                    DesktopWorkspaceMutation(
                        removed = deleteRowsFromDatabase(model.modelDbController, setOf(itemId)),
                    )
                }.fold(
                    onSuccess = { if (continuation.isActive) continuation.resumeWith(Result.success(it)) },
                    onFailure = { if (continuation.isActive) continuation.resumeWithException(it) },
                )
            }
        }
    }

    override suspend fun findLocation(itemId: String): DesktopItemLocation? = suspendCancellableCoroutine { continuation ->
        model.enqueueModelUpdateTask(
            FindDesktopItemLocationTask(itemId) { result ->
                if (continuation.isActive) continuation.resumeWith(result)
            },
        )
    }

    override suspend fun reveal(
        location: DesktopItemLocation,
        animation: DesktopRevealAnimation,
    ): DesktopItemRevealStatus = withContext(Dispatchers.Main.immediate) {
        val launcher = LawnchairLauncher.instance ?: return@withContext DesktopItemRevealStatus.UiNotReady
        launcher.getMinusOneCoordinator().requestCloseCommand(MinusOneAnimation.Immediate)
        AbstractFloatingView.closeAllOpenViews(launcher, false)

        launcher.stateManager.goToState(
            LauncherState.NORMAL,
            animation == DesktopRevealAnimation.Animated,
        )
        val stateReady = withTimeoutOrNull(UI_READY_TIMEOUT_MILLIS) {
            while (!launcher.stateManager.isInStableState(LauncherState.NORMAL)) {
                delay(UI_READY_POLL_INTERVAL_MILLIS)
            }
            true
        } ?: false
        if (!stateReady) return@withContext DesktopItemRevealStatus.UiNotReady

        revealLocation(launcher, location, animation)
    }

    override suspend fun open(itemId: String): DesktopItemOpenStatus {
        val item = findItem(itemId) ?: return DesktopItemOpenStatus.NotOpenable
        return withContext(Dispatchers.Main.immediate) {
            val launcher = LawnchairLauncher.instance
                ?: return@withContext DesktopItemOpenStatus.UiNotReady
            val itemView = withTimeoutOrNull(UI_READY_TIMEOUT_MILLIS) {
                var view = findBoundItemView(launcher, item)
                while (
                    view == null ||
                    view.windowToken == null ||
                    !launcher.workspace.isFinishedSwitchingState
                ) {
                    delay(UI_READY_POLL_INTERVAL_MILLIS)
                    view = findBoundItemView(launcher, item)
                }
                view
            } ?: return@withContext DesktopItemOpenStatus.UiNotReady

            if (itemView.performClick()) {
                DesktopItemOpenStatus.Opened
            } else {
                DesktopItemOpenStatus.NotOpenable
            }
        }
    }

    private suspend fun findItem(itemId: String): WorkspaceItemInfo? = suspendCancellableCoroutine { continuation ->
        model.enqueueModelUpdateTask(
            FindDesktopItemTask(itemId) { result ->
                if (continuation.isActive) continuation.resumeWith(result)
            },
        )
    }

    private fun findBoundItemView(
        launcher: LawnchairLauncher,
        item: WorkspaceItemInfo,
    ): View? {
        launcher.workspace.getHomescreenIconByItemId(item.id)?.let { return it }
        if (item.container < 0) return null
        val folder = launcher.findFolderIcon(item.container)?.folder ?: return null
        var match: View? = null
        folder.iterateOverItems { info, view ->
            (info.id == item.id).also { isMatch ->
                if (isMatch) match = view
            }
        }
        return match
    }

    private suspend fun revealLocation(
        launcher: LawnchairLauncher,
        location: DesktopItemLocation,
        animation: DesktopRevealAnimation,
    ): DesktopItemRevealStatus {
        return when (location) {
            is DesktopItemLocation.Workspace -> revealWorkspacePage(launcher, location.screenId, animation)

            DesktopItemLocation.Hotseat -> DesktopItemRevealStatus.Revealed

            is DesktopItemLocation.Folder -> {
                val parentResult = revealLocation(launcher, location.parent, animation)
                if (parentResult != DesktopItemRevealStatus.Revealed) return parentResult
                val folderIcon = withTimeoutOrNull(UI_READY_TIMEOUT_MILLIS) {
                    var icon = launcher.findFolderIcon(location.folderId)
                    while (icon == null) {
                        delay(UI_READY_POLL_INTERVAL_MILLIS)
                        icon = launcher.findFolderIcon(location.folderId)
                    }
                    icon
                } ?: return DesktopItemRevealStatus.UiNotReady
                val folder = folderIcon.folder
                folder.animateOpen()
                val content = folder.content
                val pageIndex = location.itemRank / content.itemsPerPage()
                if (pageIndex >= content.pageCount) {
                    return DesktopItemRevealStatus.NotRevealable
                }
                if (animation == DesktopRevealAnimation.Animated) {
                    content.snapToPage(pageIndex)
                } else {
                    content.snapToPageImmediately(pageIndex)
                }
                val pageReady = withTimeoutOrNull(UI_READY_TIMEOUT_MILLIS) {
                    while (content.nextPage != pageIndex || content.isPageInTransition) {
                        delay(UI_READY_POLL_INTERVAL_MILLIS)
                    }
                    true
                } ?: false
                if (pageReady) {
                    DesktopItemRevealStatus.Revealed
                } else {
                    DesktopItemRevealStatus.UiNotReady
                }
            }

            DesktopItemLocation.Unsupported -> DesktopItemRevealStatus.NotRevealable
        }
    }

    private suspend fun revealWorkspacePage(
        launcher: LawnchairLauncher,
        screenId: Int,
        animation: DesktopRevealAnimation,
    ): DesktopItemRevealStatus {
        val workspace = launcher.workspace
        val pageIndex = withTimeoutOrNull(UI_READY_TIMEOUT_MILLIS) {
            var index = workspace.getPageIndexForScreenId(screenId)
            while (index < 0) {
                delay(UI_READY_POLL_INTERVAL_MILLIS)
                index = workspace.getPageIndexForScreenId(screenId)
            }
            index
        } ?: return DesktopItemRevealStatus.UiNotReady

        if (animation == DesktopRevealAnimation.Animated) {
            workspace.snapToPage(pageIndex)
        } else {
            workspace.snapToPageImmediately(pageIndex)
        }
        val pageReady = withTimeoutOrNull(UI_READY_TIMEOUT_MILLIS) {
            while (workspace.nextPage != pageIndex || workspace.isPageInTransition) {
                delay(UI_READY_POLL_INTERVAL_MILLIS)
            }
            true
        } ?: false
        return if (pageReady) {
            DesktopItemRevealStatus.Revealed
        } else {
            DesktopItemRevealStatus.UiNotReady
        }
    }

    private class FindDesktopItemLocationTask(
        private val stableId: String,
        private val completion: (Result<DesktopItemLocation?>) -> Unit,
    ) : LauncherModel.ModelUpdateTask {
        override fun execute(
            taskController: ModelTaskController,
            dataModel: BgDataModel,
            apps: AllAppsList,
        ) {
            completion(
                runCatching {
                    synchronized(dataModel) {
                        val item = dataModel.itemsIdMap.firstOrNull {
                            it is WorkspaceItemInfo && getStableId(it.intent) == stableId
                        } ?: return@synchronized null
                        resolveLocation(item, dataModel, hashSetOf())
                    }
                },
            )
        }

        private fun resolveLocation(
            item: ItemInfo,
            dataModel: BgDataModel,
            visitedIds: MutableSet<Int>,
        ): DesktopItemLocation {
            if (!visitedIds.add(item.id)) return DesktopItemLocation.Unsupported
            return when (item.container) {
                LauncherSettings.Favorites.CONTAINER_DESKTOP -> {
                    DesktopItemLocation.Workspace(item.screenId)
                }

                LauncherSettings.Favorites.CONTAINER_HOTSEAT,
                LauncherSettings.Favorites.CONTAINER_HOTSEAT_PREDICTION,
                -> DesktopItemLocation.Hotseat

                else -> {
                    if (item.container < 0) return DesktopItemLocation.Unsupported
                    val parent = dataModel.itemsIdMap[item.container]
                        ?: return DesktopItemLocation.Unsupported
                    DesktopItemLocation.Folder(
                        folderId = item.container,
                        itemRank = item.rank,
                        parent = resolveLocation(parent, dataModel, visitedIds),
                    )
                }
            }
        }
    }

    private class FindDesktopItemTask(
        private val stableId: String,
        private val completion: (Result<WorkspaceItemInfo?>) -> Unit,
    ) : LauncherModel.ModelUpdateTask {
        override fun execute(
            taskController: ModelTaskController,
            dataModel: BgDataModel,
            apps: AllAppsList,
        ) {
            completion(
                runCatching {
                    synchronized(dataModel) {
                        dataModel.itemsIdMap.firstOrNull {
                            it is WorkspaceItemInfo && getStableId(it.intent) == stableId
                        } as? WorkspaceItemInfo
                    }
                },
            )
        }
    }

    private class RemoveDesktopItemTask(
        private val stableId: String,
        private val completion: (Result<DesktopWorkspaceMutation>) -> Unit,
    ) : LauncherModel.ModelUpdateTask {
        override fun execute(
            taskController: ModelTaskController,
            dataModel: BgDataModel,
            apps: AllAppsList,
        ) {
            completion(
                runCatching {
                    val removedModelItems = dataModel.itemsIdMap.count { getStableId(it.intent) == stableId }
                    val matcher = Predicate<ItemInfo?> { getStableId(it?.intent) == stableId }
                    taskController.deleteAndBindComponentsRemoved(matcher, "remove sdk desktop item $stableId")
                    val removedRows = deleteRowsFromDatabase(
                        taskController.app.model.modelDbController,
                        setOf(stableId),
                    )
                    DesktopWorkspaceMutation(removed = maxOf(removedModelItems, removedRows))
                },
            )
        }
    }

    private class ReconcileDesktopItemsTask(
        specs: List<DesktopItemSpec>,
        private val removedIds: Set<String>,
        private val completion: (Result<DesktopWorkspaceMutation>) -> Unit,
    ) : LauncherModel.ModelUpdateTask {
        private val specsById = specs.associateBy(DesktopItemSpec::id)

        override fun execute(
            taskController: ModelTaskController,
            dataModel: BgDataModel,
            apps: AllAppsList,
        ) {
            completion(runCatching { reconcile(taskController, dataModel) })
        }

        private fun reconcile(
            taskController: ModelTaskController,
            dataModel: BgDataModel,
        ): DesktopWorkspaceMutation {
            val context = taskController.app.context
            val profile = taskController.app.invariantDeviceProfile
            val modelWriter = taskController.getModelWriter()
            val addedItems = arrayListOf<ItemInfo>()
            val updatedItems = arrayListOf<ItemInfo>()
            val addedScreens = IntArray()
            var removedCount = 0

            synchronized(dataModel) {
                val existingItems = dataModel.itemsIdMap.mapNotNull { item ->
                    (item as? WorkspaceItemInfo)?.takeIf {
                        it.intent.hasExtra(SdkDesktopItemActionDispatcher.EXTRA_DESKTOP_ITEM_ID)
                    }
                }
                val plan = DesktopItemsReconciler.plan(
                    desiredIds = specsById.keys,
                    removedIds = removedIds,
                    existingIds = existingItems.map { getStableId(it.intent).orEmpty() },
                )
                val keptItemsById = plan.keptRecordIndexes.associate { index ->
                    getStableId(existingItems[index].intent).orEmpty() to existingItems[index]
                }
                val deletedItemIds = plan.deletedRecordIndexes.mapTo(hashSetOf()) { existingItems[it].id }
                removedCount = deletedItemIds.size
                if (deletedItemIds.isNotEmpty()) {
                    taskController.deleteAndBindComponentsRemoved(
                        Predicate { item -> item?.id in deletedItemIds },
                        "reconcile sdk desktop items",
                    )
                }

                val grids = hashMapOf<Int, DesktopGrid>()
                fun gridFor(screenId: Int): DesktopGrid = grids.getOrPut(screenId) {
                    DesktopGrid(profile.numColumns, profile.numRows).also { grid ->
                        dataModel.itemsIdMap.forEach { item ->
                            if (item.id !in deletedItemIds &&
                                item.container == LauncherSettings.Favorites.CONTAINER_DESKTOP &&
                                item.screenId == screenId
                            ) {
                                grid.mark(item.cellX, item.cellY, item.spanX, item.spanY)
                            }
                        }
                    }
                }

                val workspaceScreens = dataModel.collectWorkspaceScreens()
                specsById.values.forEach { spec ->
                    if (spec.id in removedIds) return@forEach
                    val existing = keptItemsById[spec.id]
                    if (existing != null) {
                        if (updateExistingItem(existing, context, spec)) {
                            modelWriter.updateItemInDatabase(existing)
                            updatedItems += existing
                        }
                        return@forEach
                    }

                    val desiredRow = when (val row = spec.placement.row) {
                        is DesktopItemRowAnchor.FromTop -> row.index
                        is DesktopItemRowAnchor.FromBottom -> profile.numRows - 1 - row.index
                    }
                    if (spec.placement.column !in 0 until profile.numColumns ||
                        desiredRow !in 0 until profile.numRows
                    ) {
                        return@forEach
                    }

                    val screenId = ensureScreenIdForPage(
                        taskController,
                        workspaceScreens,
                        spec.placement.page,
                        addedScreens,
                    )
                    val grid = gridFor(screenId)
                    val cell = grid.resolve(spec.placement) ?: return@forEach
                    grid.mark(cell.column, cell.row)
                    val item = createWorkspaceItem(context, spec)
                    modelWriter.addItemToDatabase(
                        item,
                        LauncherSettings.Favorites.CONTAINER_DESKTOP,
                        screenId,
                        cell.column,
                        cell.row,
                    )
                    addedItems += item
                }

                val modelItemIds = dataModel.itemsIdMap.mapTo(hashSetOf()) { it.id }
                val orphanRows = querySdkRows(taskController.app.model.modelDbController)
                    .filter { it.rowId !in modelItemIds }
                    .map(DatabaseDesktopItem::rowId)
                deleteDatabaseRows(taskController.app.model.modelDbController, orphanRows)
            }

            if (!addedScreens.isEmpty || addedItems.isNotEmpty() || updatedItems.isNotEmpty()) {
                taskController.scheduleCallbackTask { callbacks ->
                    if (!addedScreens.isEmpty) {
                        callbacks.bindAppsAdded(
                            addedScreens,
                            arrayListOf<ItemInfo>(),
                            ArrayList(addedItems),
                        )
                    } else if (addedItems.isNotEmpty()) {
                        callbacks.bindItems(addedItems, true)
                    }
                    if (updatedItems.isNotEmpty()) callbacks.bindItemsModified(updatedItems)
                }
            }
            return DesktopWorkspaceMutation(
                added = addedItems.size,
                updated = updatedItems.size,
                removed = removedCount,
            )
        }

        private fun ensureScreenIdForPage(
            taskController: ModelTaskController,
            workspaceScreens: IntArray,
            page: Int,
            addedScreens: IntArray,
        ): Int {
            if (page == 1) {
                if (!workspaceScreens.contains(WorkspaceLayoutManager.FIRST_SCREEN_ID)) {
                    workspaceScreens.add(0, WorkspaceLayoutManager.FIRST_SCREEN_ID)
                }
                return WorkspaceLayoutManager.FIRST_SCREEN_ID
            }
            val index = page - 1
            while (workspaceScreens.size() <= index) {
                val screenId = taskController.app.model.modelDbController.getNewScreenId()
                workspaceScreens.add(screenId)
                addedScreens.add(screenId)
            }
            return workspaceScreens.get(index)
        }

        private fun createWorkspaceItem(
            context: Context,
            spec: DesktopItemSpec,
        ) = WorkspaceItemInfo().apply {
            user = Process.myUserHandle()
            itemType = LauncherSettings.Favorites.ITEM_TYPE_SEARCH_ACTION
            title = spec.title
            contentDescription = spec.title
            intent = SdkDesktopItemActionDispatcher.createPersistedIntent(context, spec, spec.id)
            bitmap = loadIcon(context, spec.iconResId)
        }

        private fun updateExistingItem(
            item: WorkspaceItemInfo,
            context: Context,
            spec: DesktopItemSpec,
        ): Boolean {
            var changed = false
            val oldIntent = item.intent
            if (item.title != spec.title) {
                item.title = spec.title
                item.contentDescription = spec.title
                changed = true
            }
            val newIntent = SdkDesktopItemActionDispatcher.createPersistedIntent(context, spec, spec.id)
            if (oldIntent.toUri(0) != newIntent.toUri(0)) {
                item.intent = newIntent
                changed = true
            }
            val oldIconRes = oldIntent.getIntExtra(
                SdkDesktopItemActionDispatcher.EXTRA_DESKTOP_ITEM_ICON_RES_ID,
                -1,
            )
            val oldIconVersion = oldIntent.getStringExtra(
                SdkDesktopItemActionDispatcher.EXTRA_DESKTOP_ITEM_ICON_VERSION,
            ).orEmpty()
            if (oldIconRes != spec.iconResId || oldIconVersion != spec.iconVersion) {
                item.bitmap = loadIcon(context, spec.iconResId)
                changed = true
            }
            return changed
        }

        private fun loadIcon(context: Context, iconResId: Int) = runCatching {
            val drawable = checkNotNull(context.getDrawable(iconResId))
            LauncherIcons.obtain(context).use { icons ->
                icons.createBadgedIconBitmap(
                    drawable,
                    BaseIconFactory.IconOptions().setUser(Process.myUserHandle()),
                )
            }
        }.getOrElse {
            LauncherAppState.getInstance(context).iconCache.getDefaultIcon(Process.myUserHandle())
        }
    }

    private companion object {
        const val MODEL_READY_TIMEOUT_MILLIS = 10_000L
        const val MODEL_READY_POLL_INTERVAL_MILLIS = 25L
        const val UI_READY_TIMEOUT_MILLIS = 10_000L
        const val UI_READY_POLL_INTERVAL_MILLIS = 16L

        fun getStableId(intent: Intent?): String? {
            return intent?.getStringExtra(SdkDesktopItemActionDispatcher.EXTRA_DESKTOP_ITEM_ID)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        }

        fun querySdkRows(modelDbController: ModelDbController): List<DatabaseDesktopItem> {
            val rows = arrayListOf<DatabaseDesktopItem>()
            modelDbController.query(
                LauncherSettings.Favorites.TABLE_NAME,
                arrayOf(LauncherSettings.Favorites._ID, LauncherSettings.Favorites.INTENT),
                null,
                null,
                null,
            ).use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(LauncherSettings.Favorites._ID)
                val intentIndex = cursor.getColumnIndexOrThrow(LauncherSettings.Favorites.INTENT)
                while (cursor.moveToNext()) {
                    val description = cursor.getString(intentIndex) ?: continue
                    val intent = runCatching { Intent.parseUri(description, 0) }.getOrNull() ?: continue
                    if (!intent.hasExtra(SdkDesktopItemActionDispatcher.EXTRA_DESKTOP_ITEM_ID)) continue
                    rows += DatabaseDesktopItem(cursor.getInt(idIndex), getStableId(intent).orEmpty())
                }
            }
            return rows
        }

        fun deleteRowsFromDatabase(controller: ModelDbController, stableIds: Set<String>): Int {
            if (stableIds.isEmpty()) return 0
            val rowIds = querySdkRows(controller)
                .filter { it.stableId in stableIds }
                .map(DatabaseDesktopItem::rowId)
            return deleteDatabaseRows(controller, rowIds)
        }

        fun deleteDatabaseRows(controller: ModelDbController, rowIds: List<Int>): Int {
            if (rowIds.isEmpty()) return 0
            val placeholders = List(rowIds.size) { "?" }.joinToString(",")
            return controller.delete(
                LauncherSettings.Favorites.TABLE_NAME,
                "${LauncherSettings.Favorites._ID} IN ($placeholders)",
                rowIds.map(Int::toString).toTypedArray(),
            )
        }
    }

    private data class DatabaseDesktopItem(val rowId: Int, val stableId: String)
}

package app.lawnchair.sdk

import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import app.lawnchair.sdk.api.DesktopItemRowAnchor
import app.lawnchair.sdk.api.DesktopItemSpec
import app.lawnchair.sdk.api.DesktopItemsSnapshot
import app.lawnchair.sdk.api.LawnchairOperationResult
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherModel
import com.android.launcher3.LauncherSettings
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

internal object SdkDesktopItemsSynchronizer {
    fun refresh(context: Context): LawnchairOperationResult {
        val appContext = context.applicationContext
        val snapshot = LawnchairSdkRegistry.getDesktopItemsSnapshot(appContext)
        if (snapshot is DesktopItemsSnapshot.Unavailable) {
            return LawnchairOperationResult.DataUnavailable
        }
        val declaredItems = (snapshot as DesktopItemsSnapshot.Ready).items
        val specs = DesktopItemSpecs.normalize(declaredItems)
        if (specs.size != declaredItems.size) {
            Log.w(TAG, "Ignored ${declaredItems.size - specs.size} invalid or duplicate desktop items")
        }
        val activeIds = specs.mapTo(linkedSetOf(), DesktopItemSpec::id)
        val removedIds = pruneAndGetRemovedItemIds(appContext, activeIds)
        val model = LauncherAppState.getInstance(appContext).model
        if (!model.isModelLoaded) return LawnchairOperationResult.LauncherNotReady
        model.enqueueModelUpdateTask(ReconcileDesktopItemsTask(specs, removedIds))
        return LawnchairOperationResult.Accepted
    }

    fun removeItem(
        context: Context,
        itemId: String,
        rememberRemoval: Boolean,
    ): LawnchairOperationResult {
        val stableId = itemId.trim()
        if (stableId.isBlank()) return LawnchairOperationResult.InvalidItemId
        val appContext = context.applicationContext
        if (rememberRemoval) setRemoved(appContext, stableId, removed = true)
        removeFromModelOrDatabase(appContext, stableId)
        return LawnchairOperationResult.Accepted
    }

    fun restoreItem(context: Context, itemId: String): LawnchairOperationResult {
        val stableId = itemId.trim()
        if (stableId.isBlank()) return LawnchairOperationResult.InvalidItemId
        setRemoved(context.applicationContext, stableId, removed = false)
        return refresh(context)
    }

    @JvmStatic
    fun onUserRemovalCommitted(context: Context, item: ItemInfo?) {
        val stableId = getStableId(item?.intent) ?: return
        setRemoved(context.applicationContext, stableId, removed = true)
    }

    private fun removeFromModelOrDatabase(context: Context, stableId: String) {
        val model = LauncherAppState.getInstance(context).model
        Executors.MODEL_EXECUTOR.execute {
            if (model.isModelLoaded) {
                model.enqueueModelUpdateTask(RemoveDesktopItemTask(stableId))
            } else {
                deleteRowsFromDatabase(model.modelDbController, setOf(stableId))
            }
        }
    }

    private fun getStableId(intent: Intent?): String? {
        return intent?.getStringExtra(SdkDesktopItemActionDispatcher.EXTRA_DESKTOP_ITEM_ID)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    private fun getRemovedItemIds(context: Context): Set<String> {
        return HashSet(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getStringSet(KEY_REMOVED_ITEM_IDS, emptySet()).orEmpty(),
        )
    }

    private fun pruneAndGetRemovedItemIds(context: Context, activeIds: Set<String>): Set<String> {
        return synchronized(removedItemsLock) {
            val current = getRemovedItemIds(context)
            val retained = current.filterTo(linkedSetOf()) { it in activeIds }
            if (retained != current) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putStringSet(KEY_REMOVED_ITEM_IDS, retained).apply()
            }
            retained
        }
    }

    private fun setRemoved(context: Context, stableId: String, removed: Boolean) {
        synchronized(removedItemsLock) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val next = prefs.getStringSet(KEY_REMOVED_ITEM_IDS, emptySet()).orEmpty().toMutableSet()
            val changed = if (removed) next.add(stableId) else next.remove(stableId)
            if (changed) prefs.edit().putStringSet(KEY_REMOVED_ITEM_IDS, next).apply()
        }
    }

    private fun querySdkRows(modelDbController: ModelDbController): List<DatabaseDesktopItem> {
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

    private fun deleteRowsFromDatabase(controller: ModelDbController, stableIds: Set<String>) {
        if (stableIds.isEmpty()) return
        deleteDatabaseRows(controller, querySdkRows(controller).filter { it.stableId in stableIds }.map { it.rowId })
    }

    private fun deleteDatabaseRows(controller: ModelDbController, rowIds: List<Int>) {
        if (rowIds.isEmpty()) return
        val placeholders = List(rowIds.size) { "?" }.joinToString(",")
        controller.delete(
            LauncherSettings.Favorites.TABLE_NAME,
            "${LauncherSettings.Favorites._ID} IN ($placeholders)",
            rowIds.map(Int::toString).toTypedArray(),
        )
    }

    private class RemoveDesktopItemTask(private val stableId: String) : LauncherModel.ModelUpdateTask {
        override fun execute(
            taskController: ModelTaskController,
            dataModel: BgDataModel,
            apps: AllAppsList,
        ) {
            val matcher = Predicate<ItemInfo?> { getStableId(it?.intent) == stableId }
            taskController.deleteAndBindComponentsRemoved(matcher, "remove sdk desktop item $stableId")
            deleteRowsFromDatabase(taskController.app.model.modelDbController, setOf(stableId))
        }
    }

    private class ReconcileDesktopItemsTask(
        specs: List<DesktopItemSpec>,
        private val removedIds: Set<String>,
    ) : LauncherModel.ModelUpdateTask {
        private val specsById = specs.associateBy(DesktopItemSpec::id)

        override fun execute(
            taskController: ModelTaskController,
            dataModel: BgDataModel,
            apps: AllAppsList,
        ) {
            val context = taskController.app.context
            val profile = taskController.app.invariantDeviceProfile
            val modelWriter = taskController.getModelWriter()
            val addedItems = arrayListOf<ItemInfo>()
            val updatedItems = arrayListOf<ItemInfo>()
            val addedScreens = IntArray()

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

            if (addedScreens.isEmpty && addedItems.isEmpty() && updatedItems.isEmpty()) return
            taskController.scheduleCallbackTask { callbacks ->
                if (!addedScreens.isEmpty) {
                    callbacks.bindAppsAdded(addedScreens, arrayListOf<ItemInfo>(), ArrayList(addedItems))
                } else if (addedItems.isNotEmpty()) {
                    callbacks.bindItems(addedItems, true)
                }
                if (updatedItems.isNotEmpty()) callbacks.bindItemsModified(updatedItems)
            }
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

        private fun createWorkspaceItem(context: Context, spec: DesktopItemSpec) = WorkspaceItemInfo().apply {
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

    private data class DatabaseDesktopItem(val rowId: Int, val stableId: String)

    private const val PREFS_NAME = "lawnchair_sdk_desktop_items"
    private const val KEY_REMOVED_ITEM_IDS = "removed_item_ids"
    private const val TAG = "SdkDesktopItems"
    private val removedItemsLock = Any()
}

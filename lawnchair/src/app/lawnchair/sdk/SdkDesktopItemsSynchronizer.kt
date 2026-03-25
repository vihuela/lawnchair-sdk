package app.lawnchair.sdk

import android.content.Context
import android.content.Intent
import android.os.Process
import app.lawnchair.sdk.api.DesktopItemClickAction
import app.lawnchair.sdk.api.DesktopItemConflictPolicy
import app.lawnchair.sdk.api.DesktopItemPlacement
import app.lawnchair.sdk.api.DesktopItemRowAnchor
import app.lawnchair.sdk.api.DesktopItemSpec
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherModel
import com.android.launcher3.LauncherSettings
import com.android.launcher3.WorkspaceLayoutManager
import com.android.launcher3.icons.BaseIconFactory
import com.android.launcher3.icons.LauncherIcons
import com.android.launcher3.model.AllAppsList
import com.android.launcher3.model.BgDataModel
import com.android.launcher3.model.ModelTaskController
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.util.GridOccupancy
import com.android.launcher3.util.IntArray
import java.security.MessageDigest

internal object SdkDesktopItemsSynchronizer {
    fun sync(context: Context) {
        val provider = LawnchairSdkRegistry.desktopItemProvider ?: return
        val items = provider.provideDesktopItems(context)
            .map(::resolveSpec)
            .filter(::isValidSpec)
        if (items.isEmpty()) return

        LauncherAppState.getInstance(context)
            .model
            .enqueueModelUpdateTask(SyncSdkDesktopItemsTask(items))
    }

    private fun resolveSpec(spec: DesktopItemSpec): ResolvedDesktopItemSpec {
        val explicitId = spec.id.trim()
        if (explicitId.isNotBlank()) {
            return ResolvedDesktopItemSpec(spec = spec, stableId = explicitId)
        }

        val signature = buildString {
            when (val action = spec.clickAction) {
                is DesktopItemClickAction.LaunchIntent -> {
                    append("intent:")
                    append(action.intent.toUri(0))
                }
                is DesktopItemClickAction.ProviderAction -> {
                    append("provider:")
                    append(action.actionId)
                }
            }
            append("|page=").append(spec.placement.page)
            append("|column=").append(spec.placement.column)
            append("|row=").append(
                when (val row = spec.placement.row) {
                    is DesktopItemRowAnchor.FromTop -> "top:${row.index}"
                    is DesktopItemRowAnchor.FromBottom -> "bottom:${row.index}"
                },
            )
        }
        return ResolvedDesktopItemSpec(
            spec = spec,
            stableId = "auto_${sha256(signature).take(24)}",
        )
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun isValidSpec(spec: ResolvedDesktopItemSpec): Boolean {
        return spec.stableId.isNotBlank() &&
            spec.spec.title.isNotBlank() &&
            spec.spec.iconResId != 0 &&
            spec.spec.placement.page > 0 &&
            spec.spec.placement.column >= 0 &&
            when (val row = spec.spec.placement.row) {
                is DesktopItemRowAnchor.FromTop -> row.index >= 0
                is DesktopItemRowAnchor.FromBottom -> row.index >= 0
            }
    }

    private class SyncSdkDesktopItemsTask(
        private val specs: List<ResolvedDesktopItemSpec>,
    ) : LauncherModel.ModelUpdateTask {
        override fun execute(
            taskController: ModelTaskController,
            dataModel: BgDataModel,
            apps: AllAppsList,
        ) {
            if (specs.isEmpty()) return

            val context = taskController.app.context
            val modelWriter = taskController.getModelWriter()
            val updatedItems = ArrayList<ItemInfo>()
            val addedItems = ArrayList<ItemInfo>()
            val addedWorkspaceScreens = IntArray()

            synchronized(dataModel) {
                val workspaceScreens = dataModel.collectWorkspaceScreens()
                specs.forEach { spec ->
                    val existingItems = findExistingItems(dataModel, spec.stableId)
                    val existingItem = existingItems.firstOrNull()
                    val duplicateItems = existingItems.drop(1)
                    if (duplicateItems.isNotEmpty()) {
                        modelWriter.deleteItemsFromDatabase(
                            duplicateItems,
                            "remove duplicate sdk desktop items for ${spec.stableId}",
                        )
                    }
                    cleanupOrphanedRows(
                        taskController = taskController,
                        stableId = spec.stableId,
                        keptItemId = existingItem?.id,
                    )

                    if (existingItem == null) {
                        val screenId = ensureScreenIdForPage(
                            taskController = taskController,
                            workspaceScreens = workspaceScreens,
                            page = spec.spec.placement.page,
                            addedWorkspaceScreens = addedWorkspaceScreens,
                        )
                        val targetCell = resolveCell(
                            appState = taskController.app,
                            dataModel = dataModel,
                            screenId = screenId,
                            placement = spec.spec.placement,
                            ignoredItemIds = existingItems.mapTo(hashSetOf()) { it.id },
                        ) ?: return@forEach
                        val item = createWorkspaceItem(context, spec)
                        modelWriter.addItemToDatabase(
                            item,
                            LauncherSettings.Favorites.CONTAINER_DESKTOP,
                            screenId,
                            targetCell.first,
                            targetCell.second,
                        )
                        addedItems.add(item)
                    } else {
                        val contentChanged = updateExistingItem(existingItem, context, spec)
                        if (contentChanged) {
                            // Keep the user's current placement after the first insertion.
                            modelWriter.updateItemInDatabase(existingItem)
                            updatedItems.add(existingItem)
                        }
                    }
                }
            }

            if (addedWorkspaceScreens.isEmpty && addedItems.isEmpty() && updatedItems.isEmpty()) {
                return
            }

            taskController.scheduleCallbackTask { callbacks ->
                if (!addedWorkspaceScreens.isEmpty) {
                    callbacks.bindAppsAdded(
                        addedWorkspaceScreens,
                        arrayListOf<ItemInfo>(),
                        ArrayList(addedItems),
                    )
                } else if (addedItems.isNotEmpty()) {
                    callbacks.bindItems(addedItems, true)
                }
                if (updatedItems.isNotEmpty()) {
                    callbacks.bindItemsModified(updatedItems)
                }
            }
        }

        private fun findExistingItems(
            dataModel: BgDataModel,
            specId: String,
        ): List<WorkspaceItemInfo> {
            val matches = ArrayList<WorkspaceItemInfo>()
            for (item in dataModel.itemsIdMap) {
                val workspaceItem = item as? WorkspaceItemInfo ?: continue
                val intent = workspaceItem.intent ?: continue
                if (intent.getStringExtra(SdkDesktopItemActionDispatcher.EXTRA_DESKTOP_ITEM_ID) == specId) {
                    matches.add(workspaceItem)
                }
            }
            return matches
        }

        private fun cleanupOrphanedRows(
            taskController: ModelTaskController,
            stableId: String,
            keptItemId: Int?,
        ) {
            val staleRowIds = ArrayList<Int>()
            taskController.app.model.modelDbController.query(
                LauncherSettings.Favorites.TABLE_NAME,
                arrayOf(
                    LauncherSettings.Favorites._ID,
                    LauncherSettings.Favorites.INTENT,
                ),
                null,
                null,
                null,
            ).use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(LauncherSettings.Favorites._ID)
                val intentIndex = cursor.getColumnIndexOrThrow(LauncherSettings.Favorites.INTENT)
                while (cursor.moveToNext()) {
                    val intentDescription = cursor.getString(intentIndex) ?: continue
                    val intent = runCatching { Intent.parseUri(intentDescription, 0) }.getOrNull() ?: continue
                    if (intent.getStringExtra(SdkDesktopItemActionDispatcher.EXTRA_DESKTOP_ITEM_ID) != stableId) {
                        continue
                    }
                    val rowId = cursor.getInt(idIndex)
                    if (rowId != keptItemId) {
                        staleRowIds.add(rowId)
                    }
                }
            }

            if (staleRowIds.isEmpty()) return

            val selectionArgs = staleRowIds.map(Int::toString).toTypedArray()
            val placeholders = Array(staleRowIds.size) { "?" }.joinToString(",")
            taskController.app.model.modelDbController.delete(
                LauncherSettings.Favorites.TABLE_NAME,
                "${LauncherSettings.Favorites._ID} IN ($placeholders)",
                selectionArgs,
            )
        }

        private fun ensureScreenIdForPage(
            taskController: ModelTaskController,
            workspaceScreens: IntArray,
            page: Int,
            addedWorkspaceScreens: IntArray,
        ): Int {
            if (page == 1) {
                if (!workspaceScreens.contains(WorkspaceLayoutManager.FIRST_SCREEN_ID)) {
                    workspaceScreens.add(0, WorkspaceLayoutManager.FIRST_SCREEN_ID)
                }
                return WorkspaceLayoutManager.FIRST_SCREEN_ID
            }

            val targetIndex = page - 1
            while (workspaceScreens.size() <= targetIndex) {
                val newScreenId = taskController.app.model.modelDbController.getNewScreenId()
                workspaceScreens.add(newScreenId)
                addedWorkspaceScreens.add(newScreenId)
            }
            return workspaceScreens.get(targetIndex)
        }

        private fun resolveCell(
            appState: LauncherAppState,
            dataModel: BgDataModel,
            screenId: Int,
            placement: DesktopItemPlacement,
            ignoredItemIds: Set<Int>,
        ): Pair<Int, Int>? {
            val profile = appState.invariantDeviceProfile
            val desiredRow = when (val row = placement.row) {
                is DesktopItemRowAnchor.FromTop -> row.index
                is DesktopItemRowAnchor.FromBottom -> profile.numRows - 1 - row.index
            }
            if (desiredRow !in 0 until profile.numRows || placement.column !in 0 until profile.numColumns) {
                return null
            }

            val occupied = GridOccupancy(profile.numColumns, profile.numRows)
            for (item in dataModel.itemsIdMap) {
                if (item.id in ignoredItemIds) continue
                if (item.container == LauncherSettings.Favorites.CONTAINER_DESKTOP && item.screenId == screenId) {
                    occupied.markCells(item, true)
                }
            }

            if (occupied.isRegionVacant(placement.column, desiredRow, 1, 1)) {
                return placement.column to desiredRow
            }
            if (placement.conflictPolicy == DesktopItemConflictPolicy.Skip) {
                return null
            }

            val rowRange = when (placement.row) {
                is DesktopItemRowAnchor.FromBottom -> desiredRow downTo 0
                is DesktopItemRowAnchor.FromTop -> desiredRow until profile.numRows
            }
            for (row in rowRange) {
                for (column in placement.column until profile.numColumns) {
                    if (occupied.isRegionVacant(column, row, 1, 1)) {
                        return column to row
                    }
                }
                for (column in 0 until placement.column) {
                    if (occupied.isRegionVacant(column, row, 1, 1)) {
                        return column to row
                    }
                }
            }
            return null
        }

        private fun createWorkspaceItem(
            context: Context,
            spec: ResolvedDesktopItemSpec,
        ): WorkspaceItemInfo {
            return WorkspaceItemInfo().apply {
                user = Process.myUserHandle()
                itemType = LauncherSettings.Favorites.ITEM_TYPE_SEARCH_ACTION
                title = spec.spec.title
                contentDescription = spec.spec.title
                intent = buildPersistedIntent(context, spec)
                bitmap = loadIcon(context, spec.spec.iconResId)
            }
        }

        private fun updateExistingItem(
            item: WorkspaceItemInfo,
            context: Context,
            spec: ResolvedDesktopItemSpec,
        ): Boolean {
            var changed = false
            val currentIntent = item.intent
            val existingIconResId =
                currentIntent?.getIntExtra(SdkDesktopItemActionDispatcher.EXTRA_DESKTOP_ITEM_ICON_RES_ID, -1)
                    ?: -1
            if (item.title != spec.spec.title) {
                item.title = spec.spec.title
                item.contentDescription = spec.spec.title
                changed = true
            }

            val newIntent = buildPersistedIntent(context, spec)
            if (currentIntent?.toUri(0) != newIntent.toUri(0)) {
                item.intent = newIntent
                changed = true
            }

            if (existingIconResId != spec.spec.iconResId) {
                item.bitmap = loadIcon(context, spec.spec.iconResId)
                changed = true
            }
            return changed
        }

        private fun buildPersistedIntent(
            context: Context,
            spec: ResolvedDesktopItemSpec,
        ): Intent {
            return SdkDesktopItemActionDispatcher.createPersistedIntent(
                context = context,
                spec = spec.spec,
                stableId = spec.stableId,
            )
        }

        private fun loadIcon(
            context: Context,
            iconResId: Int,
        ) = runCatching {
            val drawable = checkNotNull(context.getDrawable(iconResId))
            LauncherIcons.obtain(context).use { launcherIcons ->
                launcherIcons.createBadgedIconBitmap(
                    drawable,
                    BaseIconFactory.IconOptions().setUser(Process.myUserHandle()),
                )
            }
        }.getOrElse {
            LauncherAppState.getInstance(context).iconCache.getDefaultIcon(Process.myUserHandle())
        }
    }

    private data class ResolvedDesktopItemSpec(
        val spec: DesktopItemSpec,
        val stableId: String,
    )
}

package app.lawnchair.sdk.runtime

import app.lawnchair.sdk.api.DesktopItemSpec
import app.lawnchair.sdk.api.DesktopItemsSnapshot
import app.lawnchair.sdk.api.LauncherCommandResult
import app.lawnchair.sdk.api.LauncherFailureReason
import app.lawnchair.sdk.api.LauncherRejectReason

interface DesktopItemsSourcePort {
    suspend fun loadSnapshot(): DesktopItemsSnapshot
}

interface DesktopRemovalStorePort {
    fun prune(activeIds: Set<String>): Set<String>

    fun setRemoved(itemId: String, removed: Boolean)
}

interface DesktopWorkspacePort {
    val isReady: Boolean

    suspend fun reconcile(
        specs: List<DesktopItemSpec>,
        removedIds: Set<String>,
    ): DesktopWorkspaceMutation

    suspend fun remove(itemId: String): DesktopWorkspaceMutation
}

data class DesktopWorkspaceMutation(
    val added: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
)

class DesktopItemsController(
    private val source: DesktopItemsSourcePort,
    private val removalStore: DesktopRemovalStorePort,
    private val workspace: DesktopWorkspacePort,
) {
    suspend fun refresh(): LauncherCommandResult {
        val snapshot = runCatching { source.loadSnapshot() }
            .getOrElse {
                return LauncherCommandResult.Failed(
                    LauncherFailureReason.ExtensionCallbackFailed,
                    it.message,
                )
            }
        if (snapshot is DesktopItemsSnapshot.Unavailable) {
            return LauncherCommandResult.Rejected(LauncherRejectReason.DataUnavailable)
        }
        val specs = DesktopItemSpecs.normalize((snapshot as DesktopItemsSnapshot.Ready).items)
        val activeIds = specs.mapTo(linkedSetOf(), DesktopItemSpec::id)
        val removedIds = runCatching { removalStore.prune(activeIds) }
            .getOrElse { return modelFailure(it) }
        val isWorkspaceReady = runCatching { workspace.isReady }
            .getOrElse { return modelFailure(it) }
        if (!isWorkspaceReady) {
            return LauncherCommandResult.Rejected(LauncherRejectReason.LauncherNotReady)
        }
        return runWorkspaceMutation { workspace.reconcile(specs, removedIds) }
    }

    suspend fun remove(itemId: String, rememberRemoval: Boolean): LauncherCommandResult {
        val stableId = itemId.trim()
        if (stableId.isBlank()) {
            return LauncherCommandResult.Rejected(LauncherRejectReason.InvalidItemId)
        }
        if (rememberRemoval) {
            runCatching { removalStore.setRemoved(stableId, removed = true) }
                .getOrElse { return modelFailure(it) }
        }
        return runWorkspaceMutation { workspace.remove(stableId) }
    }

    suspend fun restore(itemId: String): LauncherCommandResult {
        val stableId = itemId.trim()
        if (stableId.isBlank()) {
            return LauncherCommandResult.Rejected(LauncherRejectReason.InvalidItemId)
        }
        runCatching { removalStore.setRemoved(stableId, removed = false) }
            .getOrElse { return modelFailure(it) }
        return refresh()
    }

    fun onUserRemovalCommitted(itemId: String) {
        val stableId = itemId.trim()
        if (stableId.isNotEmpty()) {
            runCatching { removalStore.setRemoved(stableId, removed = true) }
        }
    }

    private suspend fun runWorkspaceMutation(
        block: suspend () -> DesktopWorkspaceMutation,
    ): LauncherCommandResult {
        return runCatching { block() }
            .fold(
                onSuccess = {
                    LauncherCommandResult.DesktopItemsReconciled(
                        added = it.added,
                        updated = it.updated,
                        removed = it.removed,
                    )
                },
                onFailure = {
                    LauncherCommandResult.Failed(
                        LauncherFailureReason.ModelOperationFailed,
                        it.message,
                    )
                },
            )
    }

    private fun modelFailure(error: Throwable): LauncherCommandResult.Failed {
        return LauncherCommandResult.Failed(
            LauncherFailureReason.ModelOperationFailed,
            error.message,
        )
    }
}

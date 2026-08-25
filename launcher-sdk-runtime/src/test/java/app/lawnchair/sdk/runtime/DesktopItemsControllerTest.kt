package app.lawnchair.sdk.runtime

import app.lawnchair.sdk.api.DesktopItemClickAction
import app.lawnchair.sdk.api.DesktopItemPlacement
import app.lawnchair.sdk.api.DesktopItemRowAnchor
import app.lawnchair.sdk.api.DesktopItemSpec
import app.lawnchair.sdk.api.DesktopItemsSnapshot
import app.lawnchair.sdk.api.LauncherCommandResult
import app.lawnchair.sdk.api.LauncherFailureReason
import app.lawnchair.sdk.api.LauncherRejectReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopItemsControllerTest {
    @Test
    fun unavailableSnapshotPreservesWorkspace() = runBlocking {
        val fixture = fixture(snapshot = DesktopItemsSnapshot.Unavailable)

        val result = fixture.controller.refresh()

        assertEquals(rejected(LauncherRejectReason.DataUnavailable), result)
        assertEquals(0, fixture.workspace.reconcileCalls)
        assertEquals(0, fixture.store.pruneCalls)
    }

    @Test
    fun refreshNormalizesSnapshotAndPassesRetainedTombstones() = runBlocking {
        val fixture = fixture(
            snapshot = DesktopItemsSnapshot.Ready(
                listOf(spec("  weather  "), spec("weather"), spec("")),
            ),
            removedIds = setOf("weather"),
            mutation = DesktopWorkspaceMutation(added = 1, updated = 2, removed = 3),
        )

        val result = fixture.controller.refresh()

        assertEquals(LauncherCommandResult.DesktopItemsReconciled(1, 2, 3), result)
        assertEquals(listOf("weather"), fixture.workspace.lastSpecs.map(DesktopItemSpec::id))
        assertEquals(setOf("weather"), fixture.workspace.lastRemovedIds)
        assertEquals(setOf("weather"), fixture.store.lastActiveIds)
    }

    @Test
    fun refreshRejectsWhenLauncherModelIsNotReady() = runBlocking {
        val fixture = fixture(ready = false)

        val result = fixture.controller.refresh()

        assertEquals(rejected(LauncherRejectReason.LauncherNotReady), result)
        assertEquals(0, fixture.workspace.reconcileCalls)
    }

    @Test
    fun extensionFailureIsReportedWithoutMutatingWorkspace() = runBlocking {
        val fixture = fixture(sourceFailure = IllegalStateException("provider failed"))

        val result = fixture.controller.refresh()

        assertEquals(
            LauncherCommandResult.Failed(
                LauncherFailureReason.ExtensionCallbackFailed,
                "provider failed",
            ),
            result,
        )
        assertEquals(0, fixture.workspace.reconcileCalls)
    }

    @Test
    fun modelFailureIsReported() = runBlocking {
        val fixture = fixture(workspaceFailure = IllegalStateException("db failed"))

        val result = fixture.controller.refresh()

        assertEquals(
            LauncherCommandResult.Failed(LauncherFailureReason.ModelOperationFailed, "db failed"),
            result,
        )
    }

    @Test
    fun removalStoreFailureStopsRefreshBeforeWorkspaceMutation() = runBlocking {
        val fixture = fixture(storeFailure = IllegalStateException("prefs failed"))

        val result = fixture.controller.refresh()

        assertEquals(
            LauncherCommandResult.Failed(
                LauncherFailureReason.ModelOperationFailed,
                "prefs failed",
            ),
            result,
        )
        assertEquals(0, fixture.workspace.reconcileCalls)
    }

    @Test
    fun removalStoreFailureStopsRememberedRemoval() = runBlocking {
        val fixture = fixture(storeFailure = IllegalStateException("prefs failed"))

        val result = fixture.controller.remove("weather", rememberRemoval = true)

        assertEquals(
            LauncherCommandResult.Failed(
                LauncherFailureReason.ModelOperationFailed,
                "prefs failed",
            ),
            result,
        )
        assertEquals(0, fixture.workspace.removeCalls)
    }

    @Test
    fun removeRejectsBlankIdWithoutCreatingTombstone() = runBlocking {
        val fixture = fixture()

        val result = fixture.controller.remove("   ", rememberRemoval = true)

        assertEquals(rejected(LauncherRejectReason.InvalidItemId), result)
        assertTrue(fixture.store.changes.isEmpty())
        assertEquals(0, fixture.workspace.removeCalls)
    }

    @Test
    fun removeCanRememberUserChoice() = runBlocking {
        val fixture = fixture(mutation = DesktopWorkspaceMutation(removed = 1))

        val result = fixture.controller.remove("  weather ", rememberRemoval = true)

        assertEquals(LauncherCommandResult.DesktopItemsReconciled(0, 0, 1), result)
        assertEquals(listOf("weather" to true), fixture.store.changes)
        assertEquals("weather", fixture.workspace.lastRemovedItemId)
    }

    @Test
    fun transientRemoveDoesNotCreateTombstone() = runBlocking {
        val fixture = fixture()

        fixture.controller.remove("weather", rememberRemoval = false)

        assertTrue(fixture.store.changes.isEmpty())
        assertEquals("weather", fixture.workspace.lastRemovedItemId)
    }

    @Test
    fun restoreClearsTombstoneThenReconciles() = runBlocking {
        val fixture = fixture()

        fixture.controller.restore(" weather ")

        assertEquals(listOf("weather" to false), fixture.store.changes)
        assertEquals(1, fixture.workspace.reconcileCalls)
        assertEquals(0, fixture.workspace.removeCalls)
    }

    @Test
    fun committedUserRemovalRecordsOnlyValidStableId() {
        val fixture = fixture()

        fixture.controller.onUserRemovalCommitted("  weather ")
        fixture.controller.onUserRemovalCommitted("  ")

        assertEquals(listOf("weather" to true), fixture.store.changes)
        assertFalse(fixture.store.changes.any { it.first.isBlank() })
    }

    private fun fixture(
        snapshot: DesktopItemsSnapshot = DesktopItemsSnapshot.Ready(listOf(spec("weather"))),
        removedIds: Set<String> = emptySet(),
        ready: Boolean = true,
        mutation: DesktopWorkspaceMutation = DesktopWorkspaceMutation(),
        sourceFailure: Throwable? = null,
        storeFailure: Throwable? = null,
        workspaceFailure: Throwable? = null,
    ): Fixture {
        val source = FakeSource(snapshot, sourceFailure)
        val store = FakeStore(removedIds, storeFailure)
        val workspace = FakeWorkspace(ready, mutation, workspaceFailure)
        return Fixture(DesktopItemsController(source, store, workspace), store, workspace)
    }

    private fun spec(id: String) = DesktopItemSpec(
        id = id,
        title = "Weather",
        iconResId = 1,
        clickAction = DesktopItemClickAction.ProviderAction("open"),
        placement = DesktopItemPlacement(1, 0, DesktopItemRowAnchor.FromTop(0)),
    )

    private fun rejected(reason: LauncherRejectReason) = LauncherCommandResult.Rejected(reason)

    private data class Fixture(
        val controller: DesktopItemsController,
        val store: FakeStore,
        val workspace: FakeWorkspace,
    )

    private class FakeSource(
        private val snapshot: DesktopItemsSnapshot,
        private val failure: Throwable?,
    ) : DesktopItemsSourcePort {
        override suspend fun loadSnapshot(): DesktopItemsSnapshot {
            failure?.let { throw it }
            return snapshot
        }
    }

    private class FakeStore(
        private val removedIds: Set<String>,
        private val failure: Throwable?,
    ) : DesktopRemovalStorePort {
        var pruneCalls = 0
        var lastActiveIds: Set<String> = emptySet()
        val changes = mutableListOf<Pair<String, Boolean>>()

        override fun prune(activeIds: Set<String>): Set<String> {
            failure?.let { throw it }
            pruneCalls++
            lastActiveIds = activeIds
            return removedIds intersect activeIds
        }

        override fun setRemoved(itemId: String, removed: Boolean) {
            failure?.let { throw it }
            changes += itemId to removed
        }
    }

    private class FakeWorkspace(
        override val isReady: Boolean,
        private val mutation: DesktopWorkspaceMutation,
        private val failure: Throwable?,
    ) : DesktopWorkspacePort {
        var reconcileCalls = 0
        var removeCalls = 0
        var lastSpecs: List<DesktopItemSpec> = emptyList()
        var lastRemovedIds: Set<String> = emptySet()
        var lastRemovedItemId: String? = null

        override suspend fun reconcile(
            specs: List<DesktopItemSpec>,
            removedIds: Set<String>,
        ): DesktopWorkspaceMutation {
            reconcileCalls++
            lastSpecs = specs
            lastRemovedIds = removedIds
            failure?.let { throw it }
            return mutation
        }

        override suspend fun remove(itemId: String): DesktopWorkspaceMutation {
            removeCalls++
            lastRemovedItemId = itemId
            failure?.let { throw it }
            return mutation
        }
    }
}

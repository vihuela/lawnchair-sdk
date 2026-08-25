package app.lawnchair.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopItemsReconcilerTest {
    @Test
    fun addsDesiredItemsMissingFromModel() {
        val plan = DesktopItemsReconciler.plan(
            desiredIds = setOf("weather", "search"),
            removedIds = emptySet(),
            existingIds = listOf("weather"),
        )

        assertEquals(setOf(0), plan.keptRecordIndexes)
        assertEquals(emptySet<Int>(), plan.deletedRecordIndexes)
        assertEquals(setOf("search"), plan.addedIds)
    }

    @Test
    fun removesItemsOmittedByReadySnapshot() {
        val plan = DesktopItemsReconciler.plan(
            desiredIds = setOf("weather"),
            removedIds = emptySet(),
            existingIds = listOf("weather", "old-entry"),
        )

        assertEquals(setOf(0), plan.keptRecordIndexes)
        assertEquals(setOf(1), plan.deletedRecordIndexes)
        assertEquals(emptySet<String>(), plan.addedIds)
    }

    @Test
    fun tombstoneDeletesExistingItemAndPreventsReAdd() {
        val plan = DesktopItemsReconciler.plan(
            desiredIds = setOf("weather"),
            removedIds = setOf("weather"),
            existingIds = listOf("weather"),
        )

        assertEquals(emptySet<Int>(), plan.keptRecordIndexes)
        assertEquals(setOf(0), plan.deletedRecordIndexes)
        assertEquals(emptySet<String>(), plan.addedIds)
    }

    @Test
    fun undoWithoutTombstoneKeepsExistingItem() {
        val plan = DesktopItemsReconciler.plan(
            desiredIds = setOf("weather"),
            removedIds = emptySet(),
            existingIds = listOf("weather"),
        )

        assertEquals(setOf(0), plan.keptRecordIndexes)
        assertEquals(emptySet<Int>(), plan.deletedRecordIndexes)
        assertEquals(emptySet<String>(), plan.addedIds)
    }

    @Test
    fun duplicateRowsKeepOnlyFirstModelItem() {
        val plan = DesktopItemsReconciler.plan(
            desiredIds = setOf("weather"),
            removedIds = emptySet(),
            existingIds = listOf("weather", "weather", "weather"),
        )

        assertEquals(setOf(0), plan.keptRecordIndexes)
        assertEquals(setOf(1, 2), plan.deletedRecordIndexes)
        assertEquals(emptySet<String>(), plan.addedIds)
    }

    @Test
    fun emptyReadySnapshotRemovesAllManagedItems() {
        val plan = DesktopItemsReconciler.plan(
            desiredIds = emptySet(),
            removedIds = emptySet(),
            existingIds = listOf("one", "two"),
        )

        assertEquals(setOf(0, 1), plan.deletedRecordIndexes)
        assertEquals(emptySet<String>(), plan.addedIds)
    }
}

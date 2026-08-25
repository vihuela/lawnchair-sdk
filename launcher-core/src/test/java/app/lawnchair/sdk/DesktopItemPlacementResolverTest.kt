package app.lawnchair.sdk

import app.lawnchair.sdk.api.DesktopItemConflictPolicy
import app.lawnchair.sdk.api.DesktopItemPlacement
import app.lawnchair.sdk.api.DesktopItemRowAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopItemPlacementResolverTest {
    @Test
    fun batchInsertMarksEachResolvedCellImmediately() {
        val grid = DesktopGrid(columns = 4, rows = 4)
        val placement = placement(column = 0, row = DesktopItemRowAnchor.FromTop(0))

        val first = grid.resolve(placement)
        grid.mark(first!!.column, first.row)
        val second = grid.resolve(placement)

        assertEquals(DesktopCell(0, 0), first)
        assertEquals(DesktopCell(1, 0), second)
    }

    @Test
    fun fromTopSearchesForwardAndWrapsColumns() {
        val grid = DesktopGrid(columns = 3, rows = 3)
        grid.mark(column = 1, row = 1)
        grid.mark(column = 2, row = 1)

        val cell = grid.resolve(placement(column = 1, row = DesktopItemRowAnchor.FromTop(1)))

        assertEquals(DesktopCell(0, 1), cell)
    }

    @Test
    fun fromBottomSearchesTowardTop() {
        val grid = DesktopGrid(columns = 2, rows = 4)
        grid.mark(column = 0, row = 3)
        grid.mark(column = 1, row = 3)

        val cell = grid.resolve(placement(column = 0, row = DesktopItemRowAnchor.FromBottom(0)))

        assertEquals(DesktopCell(0, 2), cell)
    }

    @Test
    fun skipPolicyDoesNotMoveConflictingItem() {
        val grid = DesktopGrid(columns = 2, rows = 2)
        grid.mark(column = 0, row = 0)

        val cell = grid.resolve(
            placement(
                column = 0,
                row = DesktopItemRowAnchor.FromTop(0),
                conflictPolicy = DesktopItemConflictPolicy.Skip,
            ),
        )

        assertNull(cell)
    }

    @Test
    fun outOfBoundsPlacementIsRejected() {
        val grid = DesktopGrid(columns = 2, rows = 2)

        assertNull(grid.resolve(placement(column = 2, row = DesktopItemRowAnchor.FromTop(0))))
        assertNull(grid.resolve(placement(column = 0, row = DesktopItemRowAnchor.FromBottom(2))))
    }

    @Test
    fun fullGridHasNoNearestCell() {
        val grid = DesktopGrid(columns = 2, rows = 2)
        grid.mark(column = 0, row = 0, spanX = 2, spanY = 2)

        assertNull(grid.resolve(placement(column = 0, row = DesktopItemRowAnchor.FromTop(0))))
    }

    private fun placement(
        column: Int,
        row: DesktopItemRowAnchor,
        conflictPolicy: DesktopItemConflictPolicy = DesktopItemConflictPolicy.FindNearest,
    ) = DesktopItemPlacement(
        page = 1,
        column = column,
        row = row,
        conflictPolicy = conflictPolicy,
    )
}

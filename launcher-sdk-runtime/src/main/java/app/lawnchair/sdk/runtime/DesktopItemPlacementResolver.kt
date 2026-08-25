package app.lawnchair.sdk.runtime

import app.lawnchair.sdk.api.DesktopItemConflictPolicy
import app.lawnchair.sdk.api.DesktopItemPlacement
import app.lawnchair.sdk.api.DesktopItemRowAnchor
import app.lawnchair.sdk.api.DesktopItemSpec

object DesktopItemSpecs {
    fun normalize(specs: List<DesktopItemSpec>): List<DesktopItemSpec> {
        val seenIds = hashSetOf<String>()
        return specs.mapNotNull { original ->
            val spec = original.copy(id = original.id.trim())
            spec.takeIf { isValid(it) && seenIds.add(it.id) }
        }
    }

    private fun isValid(spec: DesktopItemSpec): Boolean {
        return spec.id.isNotBlank() && spec.title.isNotBlank() && spec.iconResId != 0 &&
            spec.placement.page > 0 && spec.placement.column >= 0 &&
            when (val row = spec.placement.row) {
                is DesktopItemRowAnchor.FromTop -> row.index >= 0
                is DesktopItemRowAnchor.FromBottom -> row.index >= 0
            }
    }
}

data class DesktopCell(val column: Int, val row: Int)

class DesktopGrid(private val columns: Int, private val rows: Int) {
    private val occupied = BooleanArray(columns * rows)

    fun mark(column: Int, row: Int, spanX: Int = 1, spanY: Int = 1, value: Boolean = true) {
        for (y in row until row + spanY) {
            for (x in column until column + spanX) {
                if (x in 0 until columns && y in 0 until rows) occupied[y * columns + x] = value
            }
        }
    }

    fun resolve(placement: DesktopItemPlacement): DesktopCell? {
        val desiredRow = when (val anchor = placement.row) {
            is DesktopItemRowAnchor.FromTop -> anchor.index
            is DesktopItemRowAnchor.FromBottom -> rows - 1 - anchor.index
        }
        if (placement.column !in 0 until columns || desiredRow !in 0 until rows) return null
        if (isVacant(placement.column, desiredRow)) return DesktopCell(placement.column, desiredRow)
        if (placement.conflictPolicy == DesktopItemConflictPolicy.Skip) return null

        val candidateRows = when (placement.row) {
            is DesktopItemRowAnchor.FromTop -> desiredRow until rows
            is DesktopItemRowAnchor.FromBottom -> desiredRow downTo 0
        }
        for (row in candidateRows) {
            for (column in placement.column until columns) {
                if (isVacant(column, row)) return DesktopCell(column, row)
            }
            for (column in 0 until placement.column) {
                if (isVacant(column, row)) return DesktopCell(column, row)
            }
        }
        return null
    }

    private fun isVacant(column: Int, row: Int) = !occupied[row * columns + column]
}

data class DesktopReconciliationPlan(
    val keptRecordIndexes: Set<Int>,
    val deletedRecordIndexes: Set<Int>,
    val addedIds: Set<String>,
)

object DesktopItemsReconciler {
    fun plan(
        desiredIds: Set<String>,
        removedIds: Set<String>,
        existingIds: List<String>,
    ): DesktopReconciliationPlan {
        val keptIndexes = linkedSetOf<Int>()
        val deletedIndexes = linkedSetOf<Int>()
        val keptIds = linkedSetOf<String>()
        existingIds.forEachIndexed { index, id ->
            if (id in desiredIds && id !in removedIds && keptIds.add(id)) {
                keptIndexes += index
            } else {
                deletedIndexes += index
            }
        }
        return DesktopReconciliationPlan(
            keptRecordIndexes = keptIndexes,
            deletedRecordIndexes = deletedIndexes,
            addedIds = desiredIds - removedIds - keptIds,
        )
    }
}

package app.lawnchair.sdk

import app.lawnchair.sdk.api.DesktopItemClickAction
import app.lawnchair.sdk.api.DesktopItemPlacement
import app.lawnchair.sdk.api.DesktopItemRowAnchor
import app.lawnchair.sdk.api.DesktopItemSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopItemSpecsTest {
    @Test
    fun trimsRequiredStableId() {
        val result = DesktopItemSpecs.normalize(listOf(spec(id = "  weather  ")))

        assertEquals("weather", result.single().id)
    }

    @Test
    fun duplicateStableIdsKeepFirstDeclaration() {
        val result = DesktopItemSpecs.normalize(
            listOf(spec(id = "weather", title = "first"), spec(id = "weather", title = "second")),
        )

        assertEquals(1, result.size)
        assertEquals("first", result.single().title)
    }

    @Test
    fun rejectsInvalidRequiredFieldsAndPlacement() {
        val invalid = listOf(
            spec(id = ""),
            spec(id = "one", title = ""),
            spec(id = "two", iconResId = 0),
            spec(id = "three", page = 0),
            spec(id = "four", column = -1),
            spec(id = "five", row = DesktopItemRowAnchor.FromTop(-1)),
            spec(id = "six", row = DesktopItemRowAnchor.FromBottom(-1)),
        )

        assertTrue(DesktopItemSpecs.normalize(invalid).isEmpty())
    }

    private fun spec(
        id: String,
        title: String = "title",
        iconResId: Int = 1,
        page: Int = 1,
        column: Int = 0,
        row: DesktopItemRowAnchor = DesktopItemRowAnchor.FromTop(0),
    ) = DesktopItemSpec(
        id = id,
        title = title,
        iconResId = iconResId,
        clickAction = DesktopItemClickAction.ProviderAction("open"),
        placement = DesktopItemPlacement(page = page, column = column, row = row),
    )
}

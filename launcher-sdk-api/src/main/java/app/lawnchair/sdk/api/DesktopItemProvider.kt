package app.lawnchair.sdk.api

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes

interface DesktopItemProvider {
    fun provideDesktopItems(context: Context): List<DesktopItemSpec> = emptyList()

    fun onDesktopItemAction(
        context: Context,
        itemId: String,
        actionId: String,
    ): Boolean = false
}

data class DesktopItemSpec(
    val title: String,
    @DrawableRes val iconResId: Int,
    val clickAction: DesktopItemClickAction,
    val placement: DesktopItemPlacement,
    val id: String = "",
)

sealed interface DesktopItemClickAction {
    data class LaunchIntent(val intent: Intent) : DesktopItemClickAction

    data class ProviderAction(val actionId: String) : DesktopItemClickAction
}

data class DesktopItemPlacement(
    val page: Int,
    val column: Int,
    val row: DesktopItemRowAnchor,
    val conflictPolicy: DesktopItemConflictPolicy = DesktopItemConflictPolicy.FindNearest,
)

sealed interface DesktopItemRowAnchor {
    data class FromTop(val index: Int) : DesktopItemRowAnchor

    data class FromBottom(val index: Int) : DesktopItemRowAnchor
}

enum class DesktopItemConflictPolicy {
    FindNearest,
    Skip,
}

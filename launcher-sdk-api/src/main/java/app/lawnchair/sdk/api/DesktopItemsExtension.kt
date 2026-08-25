package app.lawnchair.sdk.api

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes

interface DesktopItemsExtension {
    /**
     * Returns the complete desired desktop state. [DesktopItemsSnapshot.Unavailable] keeps the
     * last applied state unchanged and should be used while data is loading or temporarily failed.
     */
    fun getSnapshot(context: Context): DesktopItemsSnapshot

    fun onAction(
        context: Context,
        itemId: String,
        actionId: String,
    ) = Unit
}

sealed interface DesktopItemsSnapshot {
    data class Ready(val items: List<DesktopItemSpec>) : DesktopItemsSnapshot

    data object Unavailable : DesktopItemsSnapshot
}

data class DesktopItemSpec(
    val id: String,
    val title: String,
    @param:DrawableRes val iconResId: Int,
    val clickAction: DesktopItemClickAction,
    val placement: DesktopItemPlacement,
    val iconVersion: String = "",
    val allowDragToDelete: Boolean = true,
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

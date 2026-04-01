package app.lawnchair.sdk.api

import android.content.Context

data class LauncherIconClickEvent(
    val title: String,
    val targetPackageName: String?,
    val isCurrentPackage: Boolean,
    val desktopItemId: String?,
)

fun interface LauncherIconClickListener {
    fun onIconClicked(context: Context, event: LauncherIconClickEvent)
}

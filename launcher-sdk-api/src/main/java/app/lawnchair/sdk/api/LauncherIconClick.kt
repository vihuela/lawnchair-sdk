package app.lawnchair.sdk.api

data class LauncherIconClickEvent(
    val title: String,
    val targetPackageName: String?,
    val isCurrentPackage: Boolean,
    val desktopItemId: String?,
)

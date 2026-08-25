package app.lawnchair.sdk.api

interface LawnchairSdkEntry {
    val minusOne: MinusOneExtension?
        get() = null

    val desktopVisibleListener: DesktopVisibleListener?
        get() = null

    val launcherIconClickListener: LauncherIconClickListener?
        get() = null

    val desktopItems: DesktopItemsExtension?
        get() = null
}

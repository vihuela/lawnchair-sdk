package app.lawnchair.sdk.api

interface LawnchairSdkEntry {
    val minusOneContentProvider: MinusOneContentProvider?
        get() = null

    val openMinusOneOnLauncherLaunch: Boolean
        get() = false

    val minusOneOverlayStateListener: MinusOneOverlayStateListener?
        get() = null

    val desktopVisibleListener: DesktopVisibleListener?
        get() = null

    val desktopItemProvider: DesktopItemProvider?
        get() = null
}

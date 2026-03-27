package app.lawnchair.sdk.api

interface LawnchairSdkEntry {
    val minusOneContentProvider: MinusOneContentProvider?
        get() = null

    val openMinusOneOnLauncherLaunch: Boolean
        get() = false

    val minusOneOverlayStateListener: MinusOneOverlayStateListener?
        get() = null

    val desktopItemProvider: DesktopItemProvider?
        get() = null
}

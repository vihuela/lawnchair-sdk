package app.lawnchair.sdk.api

interface LawnchairSdkEntry {
    val minusOneContentProvider: MinusOneContentProvider?
        get() = null

    val desktopItemProvider: DesktopItemProvider?
        get() = null
}

package app.lawnchair.sdk.api

interface LawnchairSdkEntry {
    val minusOne: MinusOneExtension?
        get() = null

    val desktopItems: DesktopItemsExtension?
        get() = null
}

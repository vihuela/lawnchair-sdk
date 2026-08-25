package app.lawnchair.sdk.api

interface LawnchairSdkEntry {
    val minusOne: MinusOneExtension?
        get() = null

    val desktopItems: DesktopItemsExtension?
        get() = null

    /** Optional suspending hook invoked before Launcher executes an app icon's click action. */
    val iconClickInterceptor: LauncherIconClickInterceptor?
        get() = null
}

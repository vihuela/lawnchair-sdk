package app.lawnchair.sdk.api

enum class DesktopVisibleSource {
    ColdStart,
    MinusOneReturn,
    AllAppsReturn,
    OtherUiReturn,
}

fun interface DesktopVisibleListener {
    fun onDesktopVisible(source: DesktopVisibleSource)
}

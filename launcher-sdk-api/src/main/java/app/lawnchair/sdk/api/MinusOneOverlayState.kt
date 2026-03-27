package app.lawnchair.sdk.api

enum class MinusOneOverlayState {
    Closed,
    Sliding,
    Opened,
}

fun interface MinusOneOverlayStateListener {
    fun onStateChanged(state: MinusOneOverlayState, progress: Float)
}

package app.lawnchair.sdk.api

data class MinusOneState(
    val phase: MinusOnePhase,
    val progress: Float,
)

enum class MinusOnePhase {
    Closed,
    Opening,
    Opened,
    Closing,
}

enum class MinusOneAnimation {
    Animated,
    Immediate,
}

enum class LawnchairOperationResult {
    Accepted,
    AlreadyInRequestedState,
    ContentUnavailable,
    DisabledByUser,
    LauncherNotReady,
    DataUnavailable,
    InvalidItemId,
    RuntimeUnavailable,
}

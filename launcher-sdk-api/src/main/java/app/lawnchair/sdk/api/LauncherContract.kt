package app.lawnchair.sdk.api

sealed interface LauncherCommand {
    data object LaunchDesktop : LauncherCommand

    data class OpenMinusOne(
        val animation: MinusOneAnimation = MinusOneAnimation.Animated,
    ) : LauncherCommand

    data class CloseMinusOne(
        val animation: MinusOneAnimation = MinusOneAnimation.Animated,
    ) : LauncherCommand

    data class ToggleMinusOne(
        val animation: MinusOneAnimation = MinusOneAnimation.Animated,
    ) : LauncherCommand

    data object RefreshDesktopItems : LauncherCommand

    data class RemoveDesktopItem(
        val itemId: String,
        val rememberRemoval: Boolean = true,
    ) : LauncherCommand

    data class RestoreDesktopItem(val itemId: String) : LauncherCommand

    data object RequestDefaultLauncher : LauncherCommand

    data object OpenHomeSettings : LauncherCommand

    data object OpenLauncherAppInfo : LauncherCommand

    /**
     * Changes the language used by the host application and Launcher UI.
     *
     * [languageTag] must be a single BCP 47 language tag, for example `zh-CN`, `en`, or `ja`.
     * Pass `null` to follow the system language again.
     */
    data class SetApplicationLanguage(val languageTag: String?) : LauncherCommand
}

sealed interface LauncherCommandResult {
    /** The command completed and did not have a more specific result payload. */
    data object Applied : LauncherCommandResult

    /** The command was handed to launcher startup and will complete after launcher becomes ready. */
    data object Queued : LauncherCommandResult

    data object AlreadyInRequestedState : LauncherCommandResult

    data class DesktopItemsReconciled(
        val added: Int,
        val updated: Int,
        val removed: Int,
    ) : LauncherCommandResult

    data class Rejected(val reason: LauncherRejectReason) : LauncherCommandResult

    data class Failed(
        val reason: LauncherFailureReason,
        val message: String? = null,
    ) : LauncherCommandResult
}

enum class LauncherRejectReason {
    ContentUnavailable,
    DisabledByUser,
    LauncherNotReady,
    DataUnavailable,
    InvalidItemId,
    InvalidLanguageTag,
}

enum class LauncherFailureReason {
    RuntimeUnavailable,
    ActivityLaunchFailed,
    ModelOperationFailed,
    ExtensionCallbackFailed,
}

data class LauncherStateSnapshot(
    val runtimeAvailable: Boolean,
    /** AOSP adapter family used by the installed runtime, for example 15 or 16. */
    val platformAdapterVersion: Int? = null,
    /** The normalized BCP 47 application language tag, or `null` when following the system. */
    val applicationLanguageTag: String? = null,
    val minusOne: MinusOneState = MinusOneState(MinusOnePhase.Closed, 0f),
)

sealed interface LauncherEvent {
    data class DesktopVisible(val source: DesktopVisibleSource) : LauncherEvent

    data class LauncherIconClicked(val event: LauncherIconClickEvent) : LauncherEvent
}

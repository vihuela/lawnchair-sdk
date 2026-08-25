package app.lawnchair.sdk.api

import android.app.Activity

data class LauncherIconClickEvent(
    val title: String,
    val targetPackageName: String?,
    val isCurrentPackage: Boolean,
    val desktopItemId: String?,
)

/**
 * Intercepts an app icon immediately before Launcher executes its original click action.
 *
 * The callback runs on the main thread and may suspend while showing an ad or other business UI.
 * Do not retain [LauncherIconClickRequest.activity] after the callback returns.
 */
fun interface LauncherIconClickInterceptor {
    suspend fun intercept(request: LauncherIconClickRequest): LauncherIconClickDecision
}

data class LauncherIconClickRequest(
    /** The currently visible Launcher activity. Suitable for displaying business UI. */
    val activity: Activity,
    val event: LauncherIconClickEvent,
)

enum class LauncherIconClickDecision {
    /** Continue the exact click action Launcher would have executed without the interceptor. */
    Proceed,

    /** Consume this click and do not execute Launcher's original action. */
    Consume,
}

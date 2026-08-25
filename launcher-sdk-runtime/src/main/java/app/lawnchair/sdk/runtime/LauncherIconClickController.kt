package app.lawnchair.sdk.runtime

import app.lawnchair.sdk.api.LauncherIconClickDecision
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/** Launcher-independent orchestration for a suspendable icon-click interception. */
class LauncherIconClickController(
    private val onInterceptorFailure: (Throwable) -> Unit = {},
) {
    private val interceptionInFlight = AtomicBoolean(false)

    /**
     * Runs at most one interception at a time. Callback failures fail open, while cancellation
     * stops the flow because the owning Launcher activity is no longer able to continue safely.
     */
    suspend fun intercept(
        callback: suspend () -> LauncherIconClickDecision,
        proceed: () -> Unit,
    ) {
        if (!interceptionInFlight.compareAndSet(false, true)) return
        try {
            val decision = try {
                callback()
            } catch (exception: CancellationException) {
                throw exception
            } catch (throwable: Throwable) {
                onInterceptorFailure(throwable)
                LauncherIconClickDecision.Proceed
            }
            if (decision == LauncherIconClickDecision.Proceed) {
                proceed()
            }
        } finally {
            interceptionInFlight.set(false)
        }
    }
}

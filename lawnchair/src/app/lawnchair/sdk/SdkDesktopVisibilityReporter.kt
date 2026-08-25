package app.lawnchair.sdk

import android.content.Intent
import android.os.Bundle
import androidx.core.view.OneShotPreDrawListener
import app.lawnchair.LawnchairLauncher
import app.lawnchair.sdk.api.DesktopVisibleSource
import app.lawnchair.sdk.api.MinusOnePhase
import com.android.launcher3.BaseActivity
import com.android.launcher3.LauncherState
import com.android.launcher3.statemanager.StateManager

internal class SdkDesktopVisibilityReporter(
    private val launcher: LawnchairLauncher,
) : StateManager.StateListener<LauncherState> {

    private var pendingSource: DesktopVisibleSource? = null
    private var resumeSourceAfterPause: DesktopVisibleSource? = null
    private var hasDispatchedDesktopVisible = false
    private var lastStableState: LauncherState = LauncherState.NORMAL
    private var isMinusOneVisibleOrPending = false
    private var isDispatchScheduled = false

    fun onCreate(savedInstanceState: Bundle?) {
        lastStableState = launcher.stateManager.currentStableState
        if (savedInstanceState == null) {
            pendingSource = DesktopVisibleSource.ColdStart
        }
    }

    fun onDeferredResumed() {
        if (pendingSource == null && hasDispatchedDesktopVisible) {
            pendingSource = resumeSourceAfterPause ?: DesktopVisibleSource.OtherUiReturn
        }
        resumeSourceAfterPause = null
        maybeDispatchDesktopVisible()
    }

    fun onMainIntentReceived(intent: Intent) {
        if (intent.action != Intent.ACTION_MAIN) return

        val source = when {
            isMinusOneVisibleOrPending -> DesktopVisibleSource.MinusOneReturn
            launcher.isInState(LauncherState.ALL_APPS) -> DesktopVisibleSource.AllAppsReturn
            launcher.stateManager.state != LauncherState.NORMAL -> DesktopVisibleSource.OtherUiReturn
            else -> DesktopVisibleSource.OtherUiReturn
        }
        pendingSource = source
    }

    fun onMinusOneWillOpen() {
        isMinusOneVisibleOrPending = true
        resumeSourceAfterPause = null
    }

    fun onPause() {
        if (!hasDispatchedDesktopVisible || launcher.isChangingConfigurations) {
            return
        }
        if (isMinusOneVisibleOrPending) {
            return
        }
        if (launcher.stateManager.isInStableState(LauncherState.NORMAL)) {
            resumeSourceAfterPause = DesktopVisibleSource.OtherUiReturn
        }
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus) {
            if (isDispatchScheduled) {
                launcher.dragLayer.invalidate()
            }
            maybeDispatchDesktopVisible()
        }
    }

    fun onWorkspaceBindingComplete() {
        maybeDispatchDesktopVisible()
    }

    fun onMinusOneStateChanged(state: MinusOnePhase) {
        val wasVisibleOrPending = isMinusOneVisibleOrPending
        when (state) {
            MinusOnePhase.Opened,
            MinusOnePhase.Opening,
            MinusOnePhase.Closing,
            -> {
                isMinusOneVisibleOrPending = true
            }

            MinusOnePhase.Closed -> {
                isMinusOneVisibleOrPending = false
                if (wasVisibleOrPending) {
                    setPendingSource(DesktopVisibleSource.MinusOneReturn)
                    maybeDispatchDesktopVisible()
                }
            }
        }
    }

    override fun onStateTransitionComplete(finalState: LauncherState) {
        val previousStableState = lastStableState
        lastStableState = finalState
        if (finalState != LauncherState.NORMAL) {
            return
        }

        val source = when {
            previousStableState == LauncherState.ALL_APPS -> DesktopVisibleSource.AllAppsReturn
            previousStableState != LauncherState.NORMAL -> DesktopVisibleSource.OtherUiReturn
            else -> null
        }

        if (source != null) {
            setPendingSource(source)
        }
        maybeDispatchDesktopVisible()
    }

    private fun maybeDispatchDesktopVisible() {
        if (isDispatchScheduled) {
            return
        }
        if (pendingSource == null) {
            return
        }
        if (!canDispatchAfterNextDraw()) {
            return
        }

        isDispatchScheduled = true
        OneShotPreDrawListener.add(launcher.dragLayer) {
            launcher.dragLayer.post {
                isDispatchScheduled = false
                val source = pendingSource ?: return@post
                if (!isDesktopActuallyVisible()) {
                    maybeDispatchDesktopVisible()
                    return@post
                }

                pendingSource = null
                hasDispatchedDesktopVisible = true
                LawnchairSdkRegistry.dispatchDesktopVisible(source)
            }
        }
        launcher.dragLayer.invalidate()
    }

    private fun isDesktopActuallyVisible(): Boolean {
        if (!canDispatchAfterNextDraw()) {
            return false
        }
        return launcher.dragLayer.isShown &&
            launcher.dragLayer.width > 0 &&
            launcher.dragLayer.height > 0 &&
            launcher.hasWindowFocus()
    }

    private fun canDispatchAfterNextDraw(): Boolean {
        if (!launcher.hasBeenResumed() || launcher.isDestroyed) {
            return false
        }
        if ((launcher.activityFlags and BaseActivity.ACTIVITY_STATE_DEFERRED_RESUMED) == 0) {
            return false
        }
        if (isMinusOneVisibleOrPending) {
            return false
        }
        if (launcher.isForceInvisible) {
            return false
        }
        if (launcher.isWorkspaceLoading) {
            return false
        }
        if (!launcher.hasWindowFocus()) {
            return false
        }
        return launcher.stateManager.isInStableState(LauncherState.NORMAL)
    }

    private fun setPendingSource(source: DesktopVisibleSource) {
        val current = pendingSource
        pendingSource = when {
            current == null -> source
            current.priority <= source.priority -> source
            else -> current
        }
    }

    private val DesktopVisibleSource.priority: Int
        get() = when (this) {
            DesktopVisibleSource.ColdStart -> 1

            DesktopVisibleSource.MinusOneReturn,
            DesktopVisibleSource.AllAppsReturn,
            DesktopVisibleSource.OtherUiReturn,
            -> 2
        }
}

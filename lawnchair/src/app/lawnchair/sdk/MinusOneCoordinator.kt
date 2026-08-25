package app.lawnchair.sdk

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.lawnchair.LawnchairLauncher
import app.lawnchair.nexuslauncher.LocalLauncherOverlay
import app.lawnchair.sdk.api.LawnchairOperationResult
import app.lawnchair.sdk.api.MinusOneAnimation
import app.lawnchair.sdk.api.MinusOneLaunchPolicy
import app.lawnchair.sdk.api.MinusOnePhase
import app.lawnchair.sdk.api.MinusOneScope
import app.lawnchair.sdk.api.MinusOneState
import com.android.launcher3.LauncherAppState

internal class MinusOneCoordinator(
    private val launcher: LawnchairLauncher,
    private val isEnabledByUser: () -> Boolean,
) {
    private var visibilityReporter: SdkDesktopVisibilityReporter? = null
    private var pendingOpenAnimation: MinusOneAnimation? = null
    private var isOpenRequested = false
    private var currentState by mutableStateOf(MinusOneState(MinusOnePhase.Closed, 0f))

    private val scope = object : MinusOneScope {
        override val state: MinusOneState
            get() = currentState

        override fun close(animation: MinusOneAnimation): LawnchairOperationResult {
            return requestClose(animation)
        }
    }

    val overlay = LocalLauncherOverlay(
        launcher = launcher,
        content = { modifier -> Content(scope, modifier) },
        onStateChanged = ::onOverlayStateChanged,
    )

    fun initialize(
        savedInstanceState: Bundle?,
        intent: Intent?,
        visibilityReporter: SdkDesktopVisibilityReporter,
    ) {
        this.visibilityReporter = visibilityReporter
        overlay.initialize()
        overlay.setEnabled(isAvailable())

        val coldStart = savedInstanceState == null
        val launchPolicy = runCatching { LawnchairSdkRegistry.minusOne?.launchPolicy }
            .getOrDefault(MinusOneLaunchPolicy.Desktop)
        if (coldStart && launchPolicy == MinusOneLaunchPolicy.MinusOneOnColdStart) {
            requestOpen(MinusOneAnimation.Immediate, waitForLauncher = false)
        }
        consumeIntentRequest(intent)
        onLauncherReady()
    }

    fun onNewIntent(intent: Intent) {
        consumeIntentRequest(intent)
        onLauncherReady()
    }

    fun onLauncherReady() {
        val animation = pendingOpenAnimation ?: return
        if (!LauncherAppState.getInstance(launcher).model.isModelLoaded) return
        pendingOpenAnimation = null
        openNow(animation)
    }

    fun getState(): MinusOneState = currentState

    fun requestOpen(
        animation: MinusOneAnimation,
        waitForLauncher: Boolean = true,
    ): LawnchairOperationResult {
        availabilityFailure()?.let { return it }
        if (pendingOpenAnimation != null || isOpenRequested ||
            currentState.phase == MinusOnePhase.Opened ||
            currentState.phase == MinusOnePhase.Opening
        ) {
            return LawnchairOperationResult.AlreadyInRequestedState
        }
        if (waitForLauncher && !LauncherAppState.getInstance(launcher).model.isModelLoaded) {
            pendingOpenAnimation = animation
            return LawnchairOperationResult.Accepted
        }
        openNow(animation)
        return LawnchairOperationResult.Accepted
    }

    fun requestClose(animation: MinusOneAnimation): LawnchairOperationResult {
        val isClosedOrClosing = currentState.phase == MinusOnePhase.Closed ||
            currentState.phase == MinusOnePhase.Closing
        if (pendingOpenAnimation == null && !isOpenRequested && isClosedOrClosing) {
            return LawnchairOperationResult.AlreadyInRequestedState
        }
        pendingOpenAnimation = null
        isOpenRequested = false
        overlay.hideOverlay(animation == MinusOneAnimation.Animated)
        return LawnchairOperationResult.Accepted
    }

    fun toggle(animation: MinusOneAnimation): LawnchairOperationResult {
        return if (currentState.progress > 0f || pendingOpenAnimation != null || isOpenRequested) {
            requestClose(animation)
        } else {
            requestOpen(animation)
        }
    }

    fun handleBack(): Boolean {
        if (currentState.progress <= 0f && pendingOpenAnimation == null && !isOpenRequested) {
            return false
        }
        requestClose(MinusOneAnimation.Animated)
        return true
    }

    private fun consumeIntentRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(LawnchairLauncher.EXTRA_OPEN_MINUS_ONE, false) != true) return
        val animation = intent.getStringExtra(LawnchairLauncher.EXTRA_MINUS_ONE_ANIMATION)
            ?.let { name ->
                runCatching { MinusOneAnimation.valueOf(name) }.getOrNull()
            }
            ?: MinusOneAnimation.Animated
        intent.removeExtra(LawnchairLauncher.EXTRA_OPEN_MINUS_ONE)
        intent.removeExtra(LawnchairLauncher.EXTRA_MINUS_ONE_ANIMATION)
        requestOpen(animation)
    }

    private fun openNow(animation: MinusOneAnimation) {
        isOpenRequested = true
        visibilityReporter?.onMinusOneWillOpen()
        when (animation) {
            MinusOneAnimation.Animated -> overlay.openOverlay()
            MinusOneAnimation.Immediate -> overlay.openOverlayImmediately()
        }
    }

    private fun isAvailable(): Boolean {
        return LawnchairSdkRegistry.hasMinusOne && isEnabledByUser()
    }

    private fun availabilityFailure(): LawnchairOperationResult? {
        if (!LawnchairSdkRegistry.hasMinusOne) {
            return LawnchairOperationResult.ContentUnavailable
        }
        if (!isEnabledByUser()) {
            return LawnchairOperationResult.DisabledByUser
        }
        return null
    }

    private fun onOverlayStateChanged(state: MinusOneState) {
        if (state == currentState) return
        if (state.phase == MinusOnePhase.Opening || state.phase == MinusOnePhase.Opened) {
            isOpenRequested = false
        }
        currentState = state
        visibilityReporter?.onMinusOneStateChanged(state.phase)
        LawnchairSdkRegistry.dispatchMinusOneState(state)
    }

    @Composable
    private fun Content(scope: MinusOneScope, modifier: Modifier = Modifier) {
        LawnchairSdkRegistry.minusOne?.Content(scope, modifier)
    }
}

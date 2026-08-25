package app.lawnchair.sdk.runtime

import app.lawnchair.sdk.api.LauncherCommandResult
import app.lawnchair.sdk.api.LauncherRejectReason
import app.lawnchair.sdk.api.MinusOneAnimation
import app.lawnchair.sdk.api.MinusOneLaunchPolicy
import app.lawnchair.sdk.api.MinusOnePhase
import app.lawnchair.sdk.api.MinusOneState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface MinusOneOverlayPort {
    fun setEnabled(enabled: Boolean)

    fun open(animation: MinusOneAnimation)

    fun close(animation: MinusOneAnimation)
}

interface MinusOneEnvironmentPort {
    val isContentAvailable: Boolean

    val isEnabledByUser: Boolean

    val isLauncherReady: Boolean
}

interface MinusOneObserverPort {
    fun onWillOpen() = Unit

    fun onStateChanged(state: MinusOneState) = Unit
}

class MinusOneController(
    private val overlay: MinusOneOverlayPort,
    private val environment: MinusOneEnvironmentPort,
    private val observer: MinusOneObserverPort,
) {
    private val mutableState = MutableStateFlow(MinusOneState(MinusOnePhase.Closed, 0f))
    private var pendingOpenAnimation: MinusOneAnimation? = null
    private var isOpenRequested = false

    val state: StateFlow<MinusOneState> = mutableState.asStateFlow()

    fun initialize(isColdStart: Boolean, launchPolicy: MinusOneLaunchPolicy) {
        overlay.setEnabled(isAvailable())
        if (isColdStart && launchPolicy == MinusOneLaunchPolicy.MinusOneOnColdStart) {
            requestOpen(MinusOneAnimation.Immediate, waitForLauncher = false)
        }
    }

    fun onLauncherReady() {
        val animation = pendingOpenAnimation ?: return
        if (!environment.isLauncherReady) return
        pendingOpenAnimation = null
        openNow(animation)
    }

    fun requestOpen(
        animation: MinusOneAnimation,
        waitForLauncher: Boolean = true,
    ): LauncherCommandResult {
        availabilityFailure()?.let { return it }
        val phase = state.value.phase
        if (pendingOpenAnimation != null || isOpenRequested ||
            phase == MinusOnePhase.Opened || phase == MinusOnePhase.Opening
        ) {
            return LauncherCommandResult.AlreadyInRequestedState
        }
        if (waitForLauncher && !environment.isLauncherReady) {
            pendingOpenAnimation = animation
            return LauncherCommandResult.Queued
        }
        openNow(animation)
        return LauncherCommandResult.Applied
    }

    fun requestClose(animation: MinusOneAnimation): LauncherCommandResult {
        val phase = state.value.phase
        val isClosedOrClosing = phase == MinusOnePhase.Closed || phase == MinusOnePhase.Closing
        if (pendingOpenAnimation == null && !isOpenRequested && isClosedOrClosing) {
            return LauncherCommandResult.AlreadyInRequestedState
        }
        pendingOpenAnimation = null
        isOpenRequested = false
        overlay.close(animation)
        return LauncherCommandResult.Applied
    }

    fun toggle(animation: MinusOneAnimation): LauncherCommandResult {
        val phase = state.value.phase
        return if (phase == MinusOnePhase.Opening || phase == MinusOnePhase.Opened ||
            state.value.progress > 0f || pendingOpenAnimation != null || isOpenRequested
        ) {
            requestClose(animation)
        } else {
            requestOpen(animation)
        }
    }

    fun handleBack(): Boolean {
        val phase = state.value.phase
        val isOpeningOrOpen = phase == MinusOnePhase.Opening || phase == MinusOnePhase.Opened
        if (!isOpeningOrOpen && state.value.progress <= 0f &&
            pendingOpenAnimation == null && !isOpenRequested
        ) {
            return false
        }
        requestClose(MinusOneAnimation.Animated)
        return true
    }

    fun onOverlayStateChanged(state: MinusOneState) {
        // Any overlay callback is authoritative for the command that produced it, including a
        // rejected open that settles back at the already-published Closed state.
        isOpenRequested = false
        if (state == mutableState.value) return
        mutableState.value = state
        observer.onStateChanged(state)
    }

    private fun openNow(animation: MinusOneAnimation) {
        isOpenRequested = true
        observer.onWillOpen()
        overlay.open(animation)
    }

    private fun isAvailable(): Boolean {
        return environment.isContentAvailable && environment.isEnabledByUser
    }

    private fun availabilityFailure(): LauncherCommandResult.Rejected? {
        if (!environment.isContentAvailable) {
            return LauncherCommandResult.Rejected(LauncherRejectReason.ContentUnavailable)
        }
        if (!environment.isEnabledByUser) {
            return LauncherCommandResult.Rejected(LauncherRejectReason.DisabledByUser)
        }
        return null
    }
}

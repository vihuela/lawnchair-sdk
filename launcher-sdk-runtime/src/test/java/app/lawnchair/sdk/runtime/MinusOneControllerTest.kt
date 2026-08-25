package app.lawnchair.sdk.runtime

import app.lawnchair.sdk.api.LauncherCommandResult
import app.lawnchair.sdk.api.LauncherRejectReason
import app.lawnchair.sdk.api.MinusOneAnimation
import app.lawnchair.sdk.api.MinusOneLaunchPolicy
import app.lawnchair.sdk.api.MinusOnePhase
import app.lawnchair.sdk.api.MinusOneState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinusOneControllerTest {
    @Test
    fun unavailableContentRejectsOpen() {
        val fixture = Fixture(contentAvailable = false)

        assertEquals(
            LauncherCommandResult.Rejected(LauncherRejectReason.ContentUnavailable),
            fixture.controller.requestOpen(MinusOneAnimation.Animated),
        )
        assertEquals(emptyList<OverlayCall>(), fixture.overlay.calls)
    }

    @Test
    fun disabledPreferenceRejectsOpen() {
        val fixture = Fixture(enabledByUser = false)

        assertEquals(
            LauncherCommandResult.Rejected(LauncherRejectReason.DisabledByUser),
            fixture.controller.requestOpen(MinusOneAnimation.Animated),
        )
    }

    @Test
    fun requestQueuesUntilLauncherReady() {
        val fixture = Fixture(launcherReady = false)

        assertEquals(
            LauncherCommandResult.Queued,
            fixture.controller.requestOpen(MinusOneAnimation.Animated),
        )
        assertTrue(fixture.overlay.calls.isEmpty())

        fixture.environment.launcherReady = true
        fixture.controller.onLauncherReady()

        assertEquals(listOf(OverlayCall.Open(MinusOneAnimation.Animated)), fixture.overlay.calls)
    }

    @Test
    fun closeCancelsQueuedOpen() {
        val fixture = Fixture(launcherReady = false)
        fixture.controller.requestOpen(MinusOneAnimation.Animated)

        assertEquals(
            LauncherCommandResult.Applied,
            fixture.controller.requestClose(MinusOneAnimation.Immediate),
        )
        fixture.environment.launcherReady = true
        fixture.controller.onLauncherReady()

        assertEquals(listOf(OverlayCall.Close(MinusOneAnimation.Immediate)), fixture.overlay.calls)
    }

    @Test
    fun coldStartPolicyOpensImmediatelyWithoutReadinessWait() {
        val fixture = Fixture(launcherReady = false)

        fixture.controller.initialize(
            isColdStart = true,
            launchPolicy = MinusOneLaunchPolicy.MinusOneOnColdStart,
        )

        assertEquals(
            listOf(OverlayCall.Enabled(true), OverlayCall.Open(MinusOneAnimation.Immediate)),
            fixture.overlay.calls,
        )
    }

    @Test
    fun warmStartDoesNotApplyColdStartPolicy() {
        val fixture = Fixture()

        fixture.controller.initialize(
            isColdStart = false,
            launchPolicy = MinusOneLaunchPolicy.MinusOneOnColdStart,
        )

        assertEquals(listOf(OverlayCall.Enabled(true)), fixture.overlay.calls)
    }

    @Test
    fun overlayStateIsForwardedToStateFlowAndObserver() {
        val fixture = Fixture()
        val state = MinusOneState(MinusOnePhase.Opening, 0.5f)

        fixture.controller.onOverlayStateChanged(state)

        assertEquals(state, fixture.controller.state.value)
        assertEquals(listOf(state), fixture.observer.states)
    }

    @Test
    fun backClosesVisibleOverlay() {
        val fixture = Fixture()
        fixture.controller.onOverlayStateChanged(MinusOneState(MinusOnePhase.Opened, 1f))

        assertTrue(fixture.controller.handleBack())
        assertEquals(listOf(OverlayCall.Close(MinusOneAnimation.Animated)), fixture.overlay.calls)
    }

    @Test
    fun openingAtZeroProgressStillClosesOnToggle() {
        val fixture = Fixture()
        fixture.controller.onOverlayStateChanged(MinusOneState(MinusOnePhase.Opening, 0f))

        val result = fixture.controller.toggle(MinusOneAnimation.Animated)

        assertEquals(LauncherCommandResult.Applied, result)
        assertEquals(listOf(OverlayCall.Close(MinusOneAnimation.Animated)), fixture.overlay.calls)
    }

    @Test
    fun openingAtZeroProgressConsumesBack() {
        val fixture = Fixture()
        fixture.controller.onOverlayStateChanged(MinusOneState(MinusOnePhase.Opening, 0f))

        assertTrue(fixture.controller.handleBack())
        assertEquals(listOf(OverlayCall.Close(MinusOneAnimation.Animated)), fixture.overlay.calls)
    }

    @Test
    fun backIsNotConsumedWhenClosed() {
        val fixture = Fixture()

        assertFalse(fixture.controller.handleBack())
        assertTrue(fixture.overlay.calls.isEmpty())
    }

    @Test
    fun duplicateOpenIsIdempotent() {
        val fixture = Fixture()

        assertEquals(
            LauncherCommandResult.Applied,
            fixture.controller.requestOpen(MinusOneAnimation.Animated),
        )
        assertEquals(
            LauncherCommandResult.AlreadyInRequestedState,
            fixture.controller.requestOpen(MinusOneAnimation.Animated),
        )
        assertEquals(1, fixture.observer.willOpenCount)
    }

    @Test
    fun authoritativeClosedCallbackReleasesPendingOpenRequest() {
        val fixture = Fixture()
        fixture.controller.requestOpen(MinusOneAnimation.Animated)

        fixture.controller.onOverlayStateChanged(MinusOneState(MinusOnePhase.Closed, 0f))

        assertEquals(
            LauncherCommandResult.Applied,
            fixture.controller.requestOpen(MinusOneAnimation.Animated),
        )
        assertEquals(2, fixture.observer.willOpenCount)
    }

    private class Fixture(
        contentAvailable: Boolean = true,
        enabledByUser: Boolean = true,
        launcherReady: Boolean = true,
    ) {
        val overlay = RecordingOverlay()
        val environment = MutableEnvironment(contentAvailable, enabledByUser, launcherReady)
        val observer = RecordingObserver()
        val controller = MinusOneController(overlay, environment, observer)
    }

    private class RecordingOverlay : MinusOneOverlayPort {
        val calls = mutableListOf<OverlayCall>()

        override fun setEnabled(enabled: Boolean) {
            calls += OverlayCall.Enabled(enabled)
        }

        override fun open(animation: MinusOneAnimation) {
            calls += OverlayCall.Open(animation)
        }

        override fun close(animation: MinusOneAnimation) {
            calls += OverlayCall.Close(animation)
        }
    }

    private class MutableEnvironment(
        override var isContentAvailable: Boolean,
        override var isEnabledByUser: Boolean,
        var launcherReady: Boolean,
    ) : MinusOneEnvironmentPort {
        override val isLauncherReady: Boolean
            get() = launcherReady
    }

    private class RecordingObserver : MinusOneObserverPort {
        var willOpenCount = 0
        val states = mutableListOf<MinusOneState>()

        override fun onWillOpen() {
            willOpenCount++
        }

        override fun onStateChanged(state: MinusOneState) {
            states += state
        }
    }

    private sealed interface OverlayCall {
        data class Enabled(val enabled: Boolean) : OverlayCall

        data class Open(val animation: MinusOneAnimation) : OverlayCall

        data class Close(val animation: MinusOneAnimation) : OverlayCall
    }
}

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
import app.lawnchair.sdk.api.LauncherCommandResult
import app.lawnchair.sdk.api.MinusOneAnimation
import app.lawnchair.sdk.api.MinusOneLaunchPolicy
import app.lawnchair.sdk.api.MinusOnePhase
import app.lawnchair.sdk.api.MinusOneScope
import app.lawnchair.sdk.api.MinusOneState
import app.lawnchair.sdk.runtime.MinusOneController
import app.lawnchair.sdk.runtime.MinusOneEnvironmentPort
import app.lawnchair.sdk.runtime.MinusOneObserverPort
import app.lawnchair.sdk.runtime.MinusOneOverlayPort
import com.android.launcher3.LauncherAppState

/** Android 15 adapter between launcher lifecycle/overlay APIs and the launcher-independent controller. */
internal class MinusOneCoordinator(
    private val launcher: LawnchairLauncher,
    private val isEnabledByUser: () -> Boolean,
) {
    private var visibilityReporter: SdkDesktopVisibilityReporter? = null
    private var currentState by mutableStateOf(MinusOneState(MinusOnePhase.Closed, 0f))

    private val controller by lazy(LazyThreadSafetyMode.NONE) {
        MinusOneController(
            overlay = object : MinusOneOverlayPort {
                override fun setEnabled(enabled: Boolean) {
                    overlay.setEnabled(enabled)
                }

                override fun open(animation: MinusOneAnimation) {
                    when (animation) {
                        MinusOneAnimation.Animated -> overlay.openOverlay()
                        MinusOneAnimation.Immediate -> overlay.openOverlayImmediately()
                    }
                }

                override fun close(animation: MinusOneAnimation) {
                    overlay.hideOverlay(animation == MinusOneAnimation.Animated)
                }
            },
            environment = object : MinusOneEnvironmentPort {
                override val isContentAvailable: Boolean
                    get() = LawnchairSdkRegistry.hasMinusOne

                override val isEnabledByUser: Boolean
                    get() = this@MinusOneCoordinator.isEnabledByUser()

                override val isLauncherReady: Boolean
                    get() = LauncherAppState.getInstance(launcher).model.isModelLoaded
            },
            observer = object : MinusOneObserverPort {
                override fun onWillOpen() {
                    visibilityReporter?.onMinusOneWillOpen()
                }

                override fun onStateChanged(state: MinusOneState) {
                    currentState = state
                    LawnchairRuntimeSignals.onMinusOneStateChanged(state)
                    visibilityReporter?.onMinusOneStateChanged(state.phase)
                }
            },
        )
    }

    private val scope = object : MinusOneScope {
        override val state: MinusOneState
            get() = currentState

        override fun close(animation: MinusOneAnimation): LauncherCommandResult {
            return requestCloseCommand(animation)
        }
    }

    val overlay: LocalLauncherOverlay = LocalLauncherOverlay(
        launcher = launcher,
        content = { modifier -> Content(scope, modifier) },
        onStateChanged = controller::onOverlayStateChanged,
    )

    fun initialize(
        savedInstanceState: Bundle?,
        intent: Intent?,
        visibilityReporter: SdkDesktopVisibilityReporter,
    ) {
        this.visibilityReporter = visibilityReporter
        overlay.initialize()
        val launchPolicy = runCatching { LawnchairSdkRegistry.minusOne?.launchPolicy }
            .getOrNull() ?: MinusOneLaunchPolicy.Desktop
        controller.initialize(
            isColdStart = savedInstanceState == null,
            launchPolicy = launchPolicy,
        )
        consumeIntentRequest(intent)
        controller.onLauncherReady()
    }

    fun onNewIntent(intent: Intent) {
        consumeIntentRequest(intent)
        controller.onLauncherReady()
    }

    fun onLauncherReady() {
        controller.onLauncherReady()
    }

    fun requestOpenCommand(
        animation: MinusOneAnimation,
        waitForLauncher: Boolean = true,
    ): LauncherCommandResult = controller.requestOpen(animation, waitForLauncher)

    fun requestCloseCommand(animation: MinusOneAnimation): LauncherCommandResult {
        return controller.requestClose(animation)
    }

    fun toggleCommand(animation: MinusOneAnimation): LauncherCommandResult {
        return controller.toggle(animation)
    }

    fun handleBack(): Boolean = controller.handleBack()

    private fun consumeIntentRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(LawnchairLauncher.EXTRA_OPEN_MINUS_ONE, false) != true) return
        val animation = intent.getStringExtra(LawnchairLauncher.EXTRA_MINUS_ONE_ANIMATION)
            ?.let { name -> runCatching { MinusOneAnimation.valueOf(name) }.getOrNull() }
            ?: MinusOneAnimation.Animated
        intent.removeExtra(LawnchairLauncher.EXTRA_OPEN_MINUS_ONE)
        intent.removeExtra(LawnchairLauncher.EXTRA_MINUS_ONE_ANIMATION)
        controller.requestOpen(animation)
    }

    @Composable
    private fun Content(scope: MinusOneScope, modifier: Modifier = Modifier) {
        LawnchairSdkRegistry.minusOne?.Content(scope, modifier)
    }
}

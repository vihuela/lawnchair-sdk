package app.lawnchair.sdk.api

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

interface MinusOneExtension {
    val launchPolicy: MinusOneLaunchPolicy
        get() = MinusOneLaunchPolicy.Desktop

    @Composable
    fun Content(scope: MinusOneScope, modifier: Modifier = Modifier)

    fun onStateChanged(state: MinusOneState) = Unit
}

enum class MinusOneLaunchPolicy {
    Desktop,
    MinusOneOnColdStart,
}

interface MinusOneScope {
    val state: MinusOneState

    fun close(animation: MinusOneAnimation = MinusOneAnimation.Animated): LawnchairOperationResult
}

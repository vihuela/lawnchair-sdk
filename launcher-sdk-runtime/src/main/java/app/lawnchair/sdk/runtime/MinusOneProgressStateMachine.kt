package app.lawnchair.sdk.runtime

import app.lawnchair.sdk.api.MinusOnePhase
import app.lawnchair.sdk.api.MinusOneState

class MinusOneProgressStateMachine {
    var state: MinusOneState = MinusOneState(MinusOnePhase.Closed, 0f)
        private set

    fun update(progress: Float): MinusOneState {
        val normalized = progress.coerceIn(0f, 1f)
        val previous = state
        val phase = when {
            normalized == 0f -> MinusOnePhase.Closed
            normalized == 1f -> MinusOnePhase.Opened
            normalized > previous.progress -> MinusOnePhase.Opening
            normalized < previous.progress -> MinusOnePhase.Closing
            previous.phase == MinusOnePhase.Opened -> MinusOnePhase.Closing
            previous.phase == MinusOnePhase.Closed -> MinusOnePhase.Opening
            else -> previous.phase
        }
        return MinusOneState(phase, normalized).also { state = it }
    }
}

object MinusOneGeometry {
    fun translationX(progress: Float, width: Float, isRtl: Boolean): Float {
        val direction = if (isRtl) 1f else -1f
        return direction * width * (1f - progress.coerceIn(0f, 1f))
    }

    fun dragProgressDelta(deltaX: Float, width: Float, isRtl: Boolean): Float {
        if (width <= 0f) return 0f
        val direction = if (isRtl) -1f else 1f
        return direction * deltaX / width
    }
}

/** Pure direction/ownership policy shared by the Android overlay gesture adapter. */
object MinusOneHorizontalGesturePolicy {
    /** Android's scroll direction corresponding to a physical finger drag. */
    fun contentScrollDirection(deltaX: Float): Int = when {
        deltaX > 0f -> -1
        deltaX < 0f -> 1
        else -> 0
    }

    /** Embedded content only owns horizontal gestures while the overlay is fully open. */
    fun shouldDeferToContent(
        overlayProgress: Float,
        canContentScrollInDragDirection: Boolean,
    ): Boolean = overlayProgress >= FULLY_OPEN_PROGRESS && canContentScrollInDragDirection

    private const val FULLY_OPEN_PROGRESS = 0.999f
}

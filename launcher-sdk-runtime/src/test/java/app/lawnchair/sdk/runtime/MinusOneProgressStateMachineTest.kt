package app.lawnchair.sdk.runtime

import app.lawnchair.sdk.api.MinusOnePhase
import app.lawnchair.sdk.api.MinusOneState
import org.junit.Assert.assertEquals
import org.junit.Test

class MinusOneProgressStateMachineTest {
    @Test
    fun reportsExactOpenedTerminalState() {
        val machine = MinusOneProgressStateMachine()

        assertEquals(MinusOneState(MinusOnePhase.Opening, 0.99f), machine.update(0.99f))
        assertEquals(MinusOneState(MinusOnePhase.Opened, 1f), machine.update(1f))
    }

    @Test
    fun reportsExactClosedTerminalState() {
        val machine = MinusOneProgressStateMachine()
        machine.update(1f)

        assertEquals(MinusOneState(MinusOnePhase.Closing, 0.01f), machine.update(0.01f))
        assertEquals(MinusOneState(MinusOnePhase.Closed, 0f), machine.update(0f))
    }

    @Test
    fun directionChangesPhaseWhenGestureReverses() {
        val machine = MinusOneProgressStateMachine()

        assertEquals(MinusOnePhase.Opening, machine.update(0.6f).phase)
        assertEquals(MinusOnePhase.Closing, machine.update(0.4f).phase)
        assertEquals(MinusOnePhase.Opening, machine.update(0.5f).phase)
    }

    @Test
    fun progressIsClampedBeforeClassification() {
        val machine = MinusOneProgressStateMachine()

        assertEquals(MinusOneState(MinusOnePhase.Opened, 1f), machine.update(2f))
        assertEquals(MinusOneState(MinusOnePhase.Closed, 0f), machine.update(-1f))
    }

    @Test
    fun unchangedPartialProgressPreservesDirection() {
        val machine = MinusOneProgressStateMachine()
        machine.update(0.4f)

        assertEquals(MinusOnePhase.Opening, machine.update(0.4f).phase)
    }

    @Test
    fun closedOverlayTranslatesToCorrectSideInBothLayoutDirections() {
        assertEquals(-1080f, MinusOneGeometry.translationX(0f, 1080f, isRtl = false), 0.001f)
        assertEquals(1080f, MinusOneGeometry.translationX(0f, 1080f, isRtl = true), 0.001f)
        assertEquals(0f, MinusOneGeometry.translationX(1f, 1080f, isRtl = false), 0.001f)
        assertEquals(0f, MinusOneGeometry.translationX(1f, 1080f, isRtl = true), 0.001f)
    }

    @Test
    fun openingDragHasPositiveProgressInBothLayoutDirections() {
        assertEquals(0.25f, MinusOneGeometry.dragProgressDelta(250f, 1000f, isRtl = false), 0.001f)
        assertEquals(0.25f, MinusOneGeometry.dragProgressDelta(-250f, 1000f, isRtl = true), 0.001f)
    }

    @Test
    fun contentScrollDirectionOpposesPhysicalFingerDrag() {
        assertEquals(-1, MinusOneHorizontalGesturePolicy.contentScrollDirection(10f))
        assertEquals(1, MinusOneHorizontalGesturePolicy.contentScrollDirection(-10f))
        assertEquals(0, MinusOneHorizontalGesturePolicy.contentScrollDirection(0f))
    }

    @Test
    fun fullyOpenedOverlayDefersToScrollableContent() {
        assertEquals(
            true,
            MinusOneHorizontalGesturePolicy.shouldDeferToContent(
                overlayProgress = 1f,
                canContentScrollInDragDirection = true,
            ),
        )
    }

    @Test
    fun overlayKeepsGestureWhenContentIsAtItsBoundary() {
        assertEquals(
            false,
            MinusOneHorizontalGesturePolicy.shouldDeferToContent(
                overlayProgress = 1f,
                canContentScrollInDragDirection = false,
            ),
        )
    }

    @Test
    fun partiallyOpenedOverlayKeepsGestureEvenWhenContentCanScroll() {
        assertEquals(
            false,
            MinusOneHorizontalGesturePolicy.shouldDeferToContent(
                overlayProgress = 0.8f,
                canContentScrollInDragDirection = true,
            ),
        )
    }
}

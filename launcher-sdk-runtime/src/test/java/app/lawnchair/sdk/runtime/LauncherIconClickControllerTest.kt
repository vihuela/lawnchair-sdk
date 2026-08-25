package app.lawnchair.sdk.runtime

import app.lawnchair.sdk.api.LauncherIconClickDecision
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class LauncherIconClickControllerTest {
    @Test
    fun proceedRunsOriginalFlowExactlyOnce() = runBlocking {
        val proceedCalls = AtomicInteger()

        LauncherIconClickController().intercept(
            callback = { LauncherIconClickDecision.Proceed },
            proceed = { proceedCalls.incrementAndGet() },
        )

        assertEquals(1, proceedCalls.get())
    }

    @Test
    fun consumeDoesNotRunOriginalFlow() = runBlocking {
        val proceedCalls = AtomicInteger()

        LauncherIconClickController().intercept(
            callback = { LauncherIconClickDecision.Consume },
            proceed = { proceedCalls.incrementAndGet() },
        )

        assertEquals(0, proceedCalls.get())
    }

    @Test
    fun callbackFailureIsReportedAndFailsOpen() = runBlocking {
        val expected = IllegalStateException("ad failed")
        var reported: Throwable? = null
        val proceedCalls = AtomicInteger()
        val controller = LauncherIconClickController { reported = it }

        controller.intercept(
            callback = { throw expected },
            proceed = { proceedCalls.incrementAndGet() },
        )

        assertSame(expected, reported)
        assertEquals(1, proceedCalls.get())
    }

    @Test
    fun concurrentClickIsIgnoredAndNextClickWorksAfterCompletion() = runBlocking {
        val callbackStarted = CompletableDeferred<Unit>()
        val finishCallback = CompletableDeferred<Unit>()
        val proceedCalls = AtomicInteger()
        val controller = LauncherIconClickController()

        val first = async {
            controller.intercept(
                callback = {
                    callbackStarted.complete(Unit)
                    finishCallback.await()
                    LauncherIconClickDecision.Proceed
                },
                proceed = { proceedCalls.incrementAndGet() },
            )
        }
        callbackStarted.await()

        controller.intercept(
            callback = { LauncherIconClickDecision.Proceed },
            proceed = { proceedCalls.incrementAndGet() },
        )
        assertEquals(0, proceedCalls.get())

        finishCallback.complete(Unit)
        first.await()
        controller.intercept(
            callback = { LauncherIconClickDecision.Proceed },
            proceed = { proceedCalls.incrementAndGet() },
        )

        assertEquals(2, proceedCalls.get())
    }

    @Test
    fun cancellationDoesNotProceedAndReleasesInFlightState() = runBlocking {
        val proceedCalls = AtomicInteger()
        val controller = LauncherIconClickController()

        try {
            controller.intercept(
                callback = { throw CancellationException("activity destroyed") },
                proceed = { proceedCalls.incrementAndGet() },
            )
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected.
        }

        controller.intercept(
            callback = { LauncherIconClickDecision.Proceed },
            proceed = { proceedCalls.incrementAndGet() },
        )
        assertEquals(1, proceedCalls.get())
    }
}

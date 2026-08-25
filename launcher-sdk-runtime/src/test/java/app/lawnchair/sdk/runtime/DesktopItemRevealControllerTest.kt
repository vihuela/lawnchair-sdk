package app.lawnchair.sdk.runtime

import app.lawnchair.sdk.api.DesktopRevealAnimation
import app.lawnchair.sdk.api.LauncherCommandResult
import app.lawnchair.sdk.api.LauncherFailureReason
import app.lawnchair.sdk.api.LauncherRejectReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopItemRevealControllerTest {
    @Test
    fun `blank item id is rejected before touching model`() = runBlocking {
        val port = FakePort()

        val result = DesktopItemRevealController(port).reveal("  ", DesktopRevealAnimation.Animated)

        assertEquals(LauncherCommandResult.Rejected(LauncherRejectReason.InvalidItemId), result)
        assertEquals(0, port.readyCalls)
    }

    @Test
    fun `model not ready is rejected`() = runBlocking {
        val port = FakePort(ready = false)

        val result = DesktopItemRevealController(port).reveal("news", DesktopRevealAnimation.Animated)

        assertEquals(LauncherCommandResult.Rejected(LauncherRejectReason.LauncherNotReady), result)
    }

    @Test
    fun `missing item is rejected`() = runBlocking {
        val result = DesktopItemRevealController(FakePort(location = null))
            .reveal("news", DesktopRevealAnimation.Animated)

        assertEquals(LauncherCommandResult.Rejected(LauncherRejectReason.ItemNotFound), result)
    }

    @Test
    fun `workspace location and requested animation are forwarded`() = runBlocking {
        val location = DesktopItemLocation.Workspace(screenId = 42)
        val port = FakePort(location = location)

        val result = DesktopItemRevealController(port)
            .reveal("  news  ", DesktopRevealAnimation.Immediate)

        assertEquals(LauncherCommandResult.Applied, result)
        assertEquals("news", port.foundItemId)
        assertEquals(location, port.revealedLocation)
        assertEquals(DesktopRevealAnimation.Immediate, port.revealedAnimation)
    }

    @Test
    fun `hotseat and folder locations can be revealed`() = runBlocking {
        val locations = listOf(
            DesktopItemLocation.Hotseat,
            DesktopItemLocation.Folder(7, 25, DesktopItemLocation.Workspace(12)),
        )

        locations.forEach { location ->
            val result = DesktopItemRevealController(FakePort(location = location))
                .reveal("item", DesktopRevealAnimation.Animated)
            assertEquals(LauncherCommandResult.Applied, result)
        }
    }

    @Test
    fun `open reveals location before executing item click`() = runBlocking {
        val port = FakePort()

        val result = DesktopItemRevealController(port)
            .open("  home  ", DesktopRevealAnimation.Immediate)

        assertEquals(LauncherCommandResult.Applied, result)
        assertEquals(listOf("find:home", "reveal", "open:home"), port.operations)
    }

    @Test
    fun `open is not attempted when reveal fails`() = runBlocking {
        val port = FakePort(status = DesktopItemRevealStatus.UiNotReady)

        val result = DesktopItemRevealController(port)
            .open("home", DesktopRevealAnimation.Immediate)

        assertEquals(LauncherCommandResult.Rejected(LauncherRejectReason.LauncherNotReady), result)
        assertEquals(listOf("find:home", "reveal"), port.operations)
    }

    @Test
    fun `open reports unavailable view and unsupported click separately`() = runBlocking {
        val uiNotReady = DesktopItemRevealController(
            FakePort(openStatus = DesktopItemOpenStatus.UiNotReady),
        ).open("home", DesktopRevealAnimation.Immediate)
        val notOpenable = DesktopItemRevealController(
            FakePort(openStatus = DesktopItemOpenStatus.NotOpenable),
        ).open("home", DesktopRevealAnimation.Immediate)

        assertEquals(LauncherCommandResult.Rejected(LauncherRejectReason.LauncherNotReady), uiNotReady)
        assertEquals(LauncherCommandResult.Rejected(LauncherRejectReason.ItemNotOpenable), notOpenable)
    }

    @Test
    fun `ui not ready and unsupported locations have distinct rejections`() = runBlocking {
        val uiNotReady = DesktopItemRevealController(
            FakePort(status = DesktopItemRevealStatus.UiNotReady),
        ).reveal("item", DesktopRevealAnimation.Animated)
        val unsupported = DesktopItemRevealController(
            FakePort(status = DesktopItemRevealStatus.NotRevealable),
        ).reveal("item", DesktopRevealAnimation.Animated)

        assertEquals(LauncherCommandResult.Rejected(LauncherRejectReason.LauncherNotReady), uiNotReady)
        assertEquals(LauncherCommandResult.Rejected(LauncherRejectReason.ItemNotRevealable), unsupported)
    }

    @Test
    fun `model exception is reported as model operation failure`() = runBlocking {
        val result = DesktopItemRevealController(FakePort(error = IllegalStateException("broken")))
            .reveal("item", DesktopRevealAnimation.Animated)

        assertEquals(
            LauncherCommandResult.Failed(LauncherFailureReason.ModelOperationFailed, "broken"),
            result,
        )
    }

    private class FakePort(
        private val ready: Boolean = true,
        private val location: DesktopItemLocation? = DesktopItemLocation.Workspace(1),
        private val status: DesktopItemRevealStatus = DesktopItemRevealStatus.Revealed,
        private val openStatus: DesktopItemOpenStatus = DesktopItemOpenStatus.Opened,
        private val error: Throwable? = null,
    ) : DesktopItemRevealPort {
        var readyCalls = 0
        var foundItemId: String? = null
        var revealedLocation: DesktopItemLocation? = null
        var revealedAnimation: DesktopRevealAnimation? = null
        val operations = mutableListOf<String>()

        override suspend fun awaitReady(): Boolean {
            readyCalls++
            error?.let { throw it }
            return ready
        }

        override suspend fun findLocation(itemId: String): DesktopItemLocation? {
            foundItemId = itemId
            operations += "find:$itemId"
            return location
        }

        override suspend fun reveal(
            location: DesktopItemLocation,
            animation: DesktopRevealAnimation,
        ): DesktopItemRevealStatus {
            revealedLocation = location
            revealedAnimation = animation
            operations += "reveal"
            return status
        }

        override suspend fun open(itemId: String): DesktopItemOpenStatus {
            operations += "open:$itemId"
            return openStatus
        }
    }
}

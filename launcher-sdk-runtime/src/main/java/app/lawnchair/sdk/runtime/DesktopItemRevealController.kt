package app.lawnchair.sdk.runtime

import app.lawnchair.sdk.api.DesktopRevealAnimation
import app.lawnchair.sdk.api.LauncherCommandResult
import app.lawnchair.sdk.api.LauncherFailureReason
import app.lawnchair.sdk.api.LauncherRejectReason

/** Current model location of an SDK-managed desktop item. */
sealed interface DesktopItemLocation {
    data class Workspace(val screenId: Int) : DesktopItemLocation

    data object Hotseat : DesktopItemLocation

    data class Folder(
        val folderId: Int,
        val itemRank: Int,
        val parent: DesktopItemLocation,
    ) : DesktopItemLocation

    data object Unsupported : DesktopItemLocation
}

enum class DesktopItemRevealStatus {
    Revealed,
    UiNotReady,
    NotRevealable,
}

enum class DesktopItemOpenStatus {
    Opened,
    UiNotReady,
    NotOpenable,
}

interface DesktopItemRevealPort {
    suspend fun awaitReady(): Boolean

    /** Resolves from the live model every time; implementations must not cache item positions. */
    suspend fun findLocation(itemId: String): DesktopItemLocation?

    suspend fun reveal(
        location: DesktopItemLocation,
        animation: DesktopRevealAnimation,
    ): DesktopItemRevealStatus

    /** Executes the live item's normal Launcher click flow after it has been revealed. */
    suspend fun open(itemId: String): DesktopItemOpenStatus
}

class DesktopItemRevealController(
    private val port: DesktopItemRevealPort,
) {
    suspend fun reveal(
        itemId: String,
        animation: DesktopRevealAnimation,
    ): LauncherCommandResult = navigate(itemId, animation, openAfterReveal = false)

    suspend fun open(
        itemId: String,
        animation: DesktopRevealAnimation,
    ): LauncherCommandResult = navigate(itemId, animation, openAfterReveal = true)

    private suspend fun navigate(
        itemId: String,
        animation: DesktopRevealAnimation,
        openAfterReveal: Boolean,
    ): LauncherCommandResult {
        val stableId = itemId.trim()
        if (stableId.isEmpty()) {
            return LauncherCommandResult.Rejected(LauncherRejectReason.InvalidItemId)
        }
        return runCatching {
            if (!port.awaitReady()) {
                return LauncherCommandResult.Rejected(LauncherRejectReason.LauncherNotReady)
            }
            val location = port.findLocation(stableId)
                ?: return LauncherCommandResult.Rejected(LauncherRejectReason.ItemNotFound)
            when (port.reveal(location, animation)) {
                DesktopItemRevealStatus.Revealed -> {
                    if (openAfterReveal) open(stableId) else LauncherCommandResult.Applied
                }
                DesktopItemRevealStatus.UiNotReady -> {
                    LauncherCommandResult.Rejected(LauncherRejectReason.LauncherNotReady)
                }
                DesktopItemRevealStatus.NotRevealable -> {
                    LauncherCommandResult.Rejected(LauncherRejectReason.ItemNotRevealable)
                }
            }
        }.getOrElse {
            LauncherCommandResult.Failed(LauncherFailureReason.ModelOperationFailed, it.message)
        }
    }

    private suspend fun open(itemId: String): LauncherCommandResult {
        return when (port.open(itemId)) {
            DesktopItemOpenStatus.Opened -> LauncherCommandResult.Applied
            DesktopItemOpenStatus.UiNotReady -> {
                LauncherCommandResult.Rejected(LauncherRejectReason.LauncherNotReady)
            }
            DesktopItemOpenStatus.NotOpenable -> {
                LauncherCommandResult.Rejected(LauncherRejectReason.ItemNotOpenable)
            }
        }
    }
}

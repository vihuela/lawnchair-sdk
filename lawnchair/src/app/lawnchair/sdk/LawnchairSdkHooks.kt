package app.lawnchair.sdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import app.lawnchair.LawnchairLauncher
import app.lawnchair.sdk.api.DesktopRevealAnimation
import com.android.launcher3.model.data.ItemInfo

/**
 * The only integration surface that Launcher3 source files should call.
 *
 * Keep these methods expressed in stable Launcher3 types. A 16-dev port can move the hook sites
 * without pulling controller, registry, persistence, or coroutine details into upstream code.
 */
object LawnchairSdkHooks {
    @JvmStatic
    fun onItemClick(
        activity: Activity,
        item: ItemInfo,
        intent: Intent?,
        continueOriginalFlow: Runnable,
    ): Boolean {
        val event = SdkLauncherIconClickDispatcher.createAndDispatchEvent(activity, item)
        val proceed = Runnable {
            if (!SdkDesktopItemActionDispatcher.dispatchIfNeeded(intent)) {
                continueOriginalFlow.run()
            }
        }
        if (SdkLauncherIconClickDispatcher.interceptIfNeeded(activity, event, proceed)) {
            return true
        }
        return SdkDesktopItemActionDispatcher.dispatchIfNeeded(intent)
    }

    @JvmStatic
    fun isDragToDeleteAllowed(item: ItemInfo?): Boolean {
        return SdkDesktopItemActionDispatcher.isDragToDeleteAllowed(item)
    }

    @JvmStatic
    fun onUserRemovalCommitted(context: Context, item: ItemInfo?) {
        SdkDesktopItemsSynchronizer.onUserRemovalCommitted(context, item)
    }

    @JvmStatic
    fun onLauncherStarted(context: Context, launchIntent: Intent?) {
        val request = consumeDesktopItemRequest(launchIntent)
        SdkDesktopItemsSynchronizer.onLauncherStarted(
            context = context,
            pendingItemId = request?.itemId,
            animation = request?.animation ?: DesktopRevealAnimation.Animated,
            openAfterReveal = request?.openAfterReveal ?: false,
        )
    }

    @JvmStatic
    fun onLauncherNewIntent(context: Context, launchIntent: Intent?) {
        val request = consumeDesktopItemRequest(launchIntent) ?: return
        SdkDesktopItemsSynchronizer.requestNavigation(
            context = context,
            itemId = request.itemId,
            animation = request.animation,
            openAfterReveal = request.openAfterReveal,
        )
    }

    private fun consumeDesktopItemRequest(intent: Intent?): PendingDesktopItemRequest? {
        if (intent == null) return null
        val openItemId = intent.getStringExtra(LawnchairLauncher.EXTRA_OPEN_DESKTOP_ITEM_ID)
        val revealItemId = intent.getStringExtra(LawnchairLauncher.EXTRA_REVEAL_DESKTOP_ITEM_ID)
        val itemId = openItemId ?: revealItemId ?: return null
        intent.removeExtra(LawnchairLauncher.EXTRA_OPEN_DESKTOP_ITEM_ID)
        intent.removeExtra(LawnchairLauncher.EXTRA_REVEAL_DESKTOP_ITEM_ID)
        val animation = intent
            .getStringExtra(LawnchairLauncher.EXTRA_DESKTOP_REVEAL_ANIMATION)
            ?.also { intent.removeExtra(LawnchairLauncher.EXTRA_DESKTOP_REVEAL_ANIMATION) }
            ?.let { runCatching { DesktopRevealAnimation.valueOf(it) }.getOrNull() }
            ?: DesktopRevealAnimation.Animated
        return PendingDesktopItemRequest(
            itemId = itemId,
            animation = animation,
            openAfterReveal = openItemId != null,
        )
    }

    private data class PendingDesktopItemRequest(
        val itemId: String,
        val animation: DesktopRevealAnimation,
        val openAfterReveal: Boolean,
    )
}

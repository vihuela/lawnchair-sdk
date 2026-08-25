package app.lawnchair.sdk

import android.content.Context
import android.content.Intent
import com.android.launcher3.model.data.ItemInfo

/**
 * The only integration surface that Launcher3 source files should call.
 *
 * Keep these methods expressed in stable Launcher3 types. A 16-dev port can move the hook sites
 * without pulling controller, registry, persistence, or coroutine details into upstream code.
 */
object LawnchairSdkHooks {
    @JvmStatic
    fun onItemClick(context: Context, item: ItemInfo, intent: Intent?): Boolean {
        SdkLauncherIconClickDispatcher.dispatch(context, item)
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
    fun onLauncherStarted(context: Context) {
        SdkDesktopItemsSynchronizer.onLauncherStarted(context)
    }
}

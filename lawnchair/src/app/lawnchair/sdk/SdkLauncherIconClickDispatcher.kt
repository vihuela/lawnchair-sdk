package app.lawnchair.sdk

import android.content.Context
import android.util.Log
import app.lawnchair.sdk.api.LauncherIconClickEvent
import com.android.launcher3.model.data.ItemInfo

internal object SdkLauncherIconClickDispatcher {
    @JvmStatic
    fun dispatch(context: Context, item: ItemInfo?) {
        val listener = LawnchairSdkRegistry.launcherIconClickListener ?: return
        val targetPackageName = item?.targetPackage
        runCatching {
            listener.onIconClicked(
                context,
                LauncherIconClickEvent(
                    title = item?.title?.toString().orEmpty(),
                    targetPackageName = targetPackageName,
                    isCurrentPackage = targetPackageName == context.packageName,
                    desktopItemId = item?.intent?.getStringExtra(
                        SdkDesktopItemActionDispatcher.EXTRA_DESKTOP_ITEM_ID,
                    ),
                ),
            )
        }.onFailure { Log.e(TAG, "Launcher icon click callback failed", it) }
    }

    private const val TAG = "SdkIconClickDispatcher"
}

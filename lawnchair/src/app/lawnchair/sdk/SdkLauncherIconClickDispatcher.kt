package app.lawnchair.sdk

import android.content.Context
import app.lawnchair.sdk.api.LauncherIconClickEvent
import com.android.launcher3.model.data.ItemInfo

internal object SdkLauncherIconClickDispatcher {
    @JvmStatic
    fun dispatch(context: Context, item: ItemInfo?) {
        val listener = LawnchairSdkRegistry.launcherIconClickListener ?: return
        val targetPackageName = item?.targetPackage
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
    }
}

package app.lawnchair.sdk

import android.content.Context
import app.lawnchair.sdk.api.LauncherIconClickEvent
import com.android.launcher3.model.data.ItemInfo

internal object SdkLauncherIconClickDispatcher {
    fun dispatch(context: Context, item: ItemInfo?) {
        val targetPackageName = item?.targetPackage
        val event = LauncherIconClickEvent(
            title = item?.title?.toString().orEmpty(),
            targetPackageName = targetPackageName,
            isCurrentPackage = targetPackageName == context.packageName,
            desktopItemId = item?.intent?.getStringExtra(
                SdkDesktopItemActionDispatcher.EXTRA_DESKTOP_ITEM_ID,
            ),
        )
        LawnchairRuntimeSignals.onLauncherIconClicked(event)
    }
}

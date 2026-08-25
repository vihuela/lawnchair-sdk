package app.lawnchair.sdk

import android.app.Activity
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.lawnchair.sdk.api.LauncherIconClickEvent
import app.lawnchair.sdk.api.LauncherIconClickRequest
import app.lawnchair.sdk.runtime.LauncherIconClickController
import com.android.launcher3.model.data.ItemInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal object SdkLauncherIconClickDispatcher {
    private val controller = LauncherIconClickController { throwable ->
        Log.e(TAG, "Icon click interceptor failed; continuing original flow", throwable)
    }

    fun createAndDispatchEvent(activity: Activity, item: ItemInfo?): LauncherIconClickEvent {
        val targetPackageName = item?.targetPackage
        val event = LauncherIconClickEvent(
            title = item?.title?.toString().orEmpty(),
            targetPackageName = targetPackageName,
            isCurrentPackage = targetPackageName == activity.packageName,
            desktopItemId = item?.intent?.getStringExtra(
                SdkDesktopItemActionDispatcher.EXTRA_DESKTOP_ITEM_ID,
            ),
        )
        LawnchairRuntimeSignals.onLauncherIconClicked(event)
        return event
    }

    fun interceptIfNeeded(
        activity: Activity,
        event: LauncherIconClickEvent,
        proceed: Runnable,
    ): Boolean {
        val interceptor = LawnchairSdkRegistry.iconClickInterceptor ?: return false
        activity.coroutineScope.launch {
            controller.intercept(
                callback = {
                    interceptor.intercept(
                        LauncherIconClickRequest(activity = activity, event = event),
                    )
                },
                proceed = proceed::run,
            )
        }
        return true
    }

    private val Activity.coroutineScope: CoroutineScope
        get() = (this as? LifecycleOwner)?.lifecycleScope ?: LawnchairSdkScope

    private const val TAG = "SdkIconClickInterceptor"
}

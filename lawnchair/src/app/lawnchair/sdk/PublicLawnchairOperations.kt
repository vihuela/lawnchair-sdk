package app.lawnchair.sdk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import android.util.Log
import app.lawnchair.LawnchairLauncher
import app.lawnchair.sdk.api.LawnchairOperationResult
import app.lawnchair.sdk.api.MinusOneAnimation
import app.lawnchair.sdk.api.MinusOneState
import app.lawnchair.sdk.internal.LawnchairOperationsDelegate
import app.lawnchair.sdk.internal.LawnchairSdkRuntimeBridge
import com.android.launcher3.util.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

internal object PublicLawnchairOperations : LawnchairOperationsDelegate {
    override fun isDefaultLauncher(context: Context): Boolean {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homeActivity = context.packageManager.resolveActivity(homeIntent, 0)?.activityInfo
        return homeActivity?.packageName == context.packageName
    }

    override fun launchDesktop(context: Context) {
        context.startActivity(
            Intent(context, LawnchairLauncher::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    override fun getMinusOneState(): MinusOneState? {
        val launcher = LawnchairLauncher.instance ?: return null
        return onMainThread { launcher.getMinusOneCoordinator().getState() }
    }

    override fun openMinusOne(
        context: Context,
        animation: MinusOneAnimation,
    ): LawnchairOperationResult {
        val launcher = LawnchairLauncher.instance
        if (launcher != null) {
            return onMainThread { launcher.getMinusOneCoordinator().requestOpen(animation) }
                ?: LawnchairOperationResult.RuntimeUnavailable
        }
        return runCatching {
            context.startActivity(
                Intent(context, LawnchairLauncher::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(LawnchairLauncher.EXTRA_OPEN_MINUS_ONE, true)
                    .putExtra(LawnchairLauncher.EXTRA_MINUS_ONE_ANIMATION, animation.name),
            )
            LawnchairOperationResult.Accepted
        }.onFailure { Log.e(TAG, "Unable to launch minus-one", it) }
            .getOrDefault(LawnchairOperationResult.RuntimeUnavailable)
    }

    override fun closeMinusOne(
        context: Context,
        animation: MinusOneAnimation,
    ): LawnchairOperationResult {
        val launcher = LawnchairLauncher.instance ?: return LawnchairOperationResult.LauncherNotReady
        return onMainThread { launcher.getMinusOneCoordinator().requestClose(animation) }
            ?: LawnchairOperationResult.RuntimeUnavailable
    }

    override fun toggleMinusOne(
        context: Context,
        animation: MinusOneAnimation,
    ): LawnchairOperationResult {
        val launcher = LawnchairLauncher.instance
        if (launcher != null) {
            return onMainThread { launcher.getMinusOneCoordinator().toggle(animation) }
                ?: LawnchairOperationResult.RuntimeUnavailable
        }
        return openMinusOne(context, animation)
    }

    override fun refreshDesktopItems(context: Context): LawnchairOperationResult {
        return SdkDesktopItemsSynchronizer.refresh(context)
    }

    override fun removeDesktopItem(
        context: Context,
        itemId: String,
        rememberRemoval: Boolean,
    ): LawnchairOperationResult {
        return SdkDesktopItemsSynchronizer.removeItem(context, itemId, rememberRemoval)
    }

    override fun restoreDesktopItem(
        context: Context,
        itemId: String,
    ): LawnchairOperationResult {
        return SdkDesktopItemsSynchronizer.restoreItem(context, itemId)
    }

    override fun requestSetDefaultLauncher(context: Context) {
        openHomeSettings(context)
    }

    override fun openHomeSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun openLauncherAppInfo(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun <T> onMainThread(block: () -> T): T? {
        return runCatching {
            if (Looper.myLooper() == Looper.getMainLooper()) return@runCatching block()
            val task = FutureTask(block)
            Executors.MAIN_EXECUTOR.execute(task)
            task.get(MAIN_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.onFailure { Log.e(TAG, "Launcher operation failed", it) }.getOrNull()
    }

    private const val MAIN_THREAD_TIMEOUT_SECONDS = 5L
    private const val TAG = "LawnchairOperations"
}

internal object LawnchairSdkApiInitializer {
    fun install() {
        LawnchairSdkRuntimeBridge.installOperationsDelegate(PublicLawnchairOperations)
    }
}

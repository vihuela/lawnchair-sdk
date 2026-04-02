package app.lawnchair.sdk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import app.lawnchair.LawnchairLauncher
import app.lawnchair.sdk.internal.LawnchairOperationsDelegate
import app.lawnchair.sdk.internal.LawnchairSdkRuntimeBridge

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

    override fun openMinusOneAnimated(context: Context): Boolean {
        val launcher = LawnchairLauncher.instance
        if (launcher != null) {
            return launcher.openMinusOneAnimated()
        }
        context.startActivity(
            Intent(context, LawnchairLauncher::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(LawnchairLauncher.EXTRA_OPEN_MINUS_ONE, true),
        )
        return true
    }

    override fun removeDesktopItem(context: Context, itemId: String): Boolean {
        return SdkDesktopItemsSynchronizer.removeItem(context, itemId)
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
}

internal object LawnchairSdkApiInitializer {
    fun install() {
        LawnchairSdkRuntimeBridge.installOperationsDelegate(PublicLawnchairOperations)
    }
}

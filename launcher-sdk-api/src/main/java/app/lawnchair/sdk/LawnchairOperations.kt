package app.lawnchair.sdk

import android.content.Context
import app.lawnchair.sdk.internal.LawnchairSdkRuntimeBridge

object LawnchairOperations {
    fun isDefaultLauncher(context: Context): Boolean {
        return LawnchairSdkRuntimeBridge.operationsDelegate.isDefaultLauncher(context)
    }

    fun launchDesktop(context: Context) {
        LawnchairSdkRuntimeBridge.operationsDelegate.launchDesktop(context)
    }

    fun openMinusOneAnimated(context: Context): Boolean {
        return LawnchairSdkRuntimeBridge.operationsDelegate.openMinusOneAnimated(context)
    }

    fun requestSetDefaultLauncher(context: Context) {
        LawnchairSdkRuntimeBridge.operationsDelegate.requestSetDefaultLauncher(context)
    }

    fun openHomeSettings(context: Context) {
        LawnchairSdkRuntimeBridge.operationsDelegate.openHomeSettings(context)
    }

    fun openLauncherAppInfo(context: Context) {
        LawnchairSdkRuntimeBridge.operationsDelegate.openLauncherAppInfo(context)
    }
}

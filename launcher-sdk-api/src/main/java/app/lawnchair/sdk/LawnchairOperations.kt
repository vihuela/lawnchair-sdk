package app.lawnchair.sdk

import android.content.Context
import app.lawnchair.sdk.api.LawnchairOperationResult
import app.lawnchair.sdk.api.MinusOneAnimation
import app.lawnchair.sdk.api.MinusOneState
import app.lawnchair.sdk.internal.LawnchairSdkRuntimeBridge

object LawnchairOperations {
    fun isDefaultLauncher(context: Context): Boolean {
        return LawnchairSdkRuntimeBridge.operationsDelegate.isDefaultLauncher(context)
    }

    fun launchDesktop(context: Context) {
        LawnchairSdkRuntimeBridge.operationsDelegate.launchDesktop(context)
    }

    fun getMinusOneState(): MinusOneState? {
        return LawnchairSdkRuntimeBridge.operationsDelegate.getMinusOneState()
    }

    fun openMinusOne(
        context: Context,
        animation: MinusOneAnimation = MinusOneAnimation.Animated,
    ): LawnchairOperationResult {
        return LawnchairSdkRuntimeBridge.operationsDelegate.openMinusOne(context, animation)
    }

    fun closeMinusOne(
        context: Context,
        animation: MinusOneAnimation = MinusOneAnimation.Animated,
    ): LawnchairOperationResult {
        return LawnchairSdkRuntimeBridge.operationsDelegate.closeMinusOne(context, animation)
    }

    fun toggleMinusOne(
        context: Context,
        animation: MinusOneAnimation = MinusOneAnimation.Animated,
    ): LawnchairOperationResult {
        return LawnchairSdkRuntimeBridge.operationsDelegate.toggleMinusOne(context, animation)
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

    fun refreshDesktopItems(context: Context): LawnchairOperationResult {
        return LawnchairSdkRuntimeBridge.operationsDelegate.refreshDesktopItems(context)
    }

    fun removeDesktopItem(
        context: Context,
        itemId: String,
        rememberRemoval: Boolean = true,
    ): LawnchairOperationResult {
        return LawnchairSdkRuntimeBridge.operationsDelegate.removeDesktopItem(
            context,
            itemId,
            rememberRemoval,
        )
    }

    fun restoreDesktopItem(context: Context, itemId: String): LawnchairOperationResult {
        return LawnchairSdkRuntimeBridge.operationsDelegate.restoreDesktopItem(context, itemId)
    }
}

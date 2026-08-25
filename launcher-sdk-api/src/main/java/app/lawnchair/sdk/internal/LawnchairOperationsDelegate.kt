package app.lawnchair.sdk.internal

import android.content.Context
import android.util.Log
import app.lawnchair.sdk.api.LawnchairOperationResult
import app.lawnchair.sdk.api.MinusOneAnimation
import app.lawnchair.sdk.api.MinusOneState

interface LawnchairOperationsDelegate {
    fun isDefaultLauncher(context: Context): Boolean

    fun launchDesktop(context: Context)

    fun getMinusOneState(): MinusOneState?

    fun openMinusOne(context: Context, animation: MinusOneAnimation): LawnchairOperationResult

    fun closeMinusOne(context: Context, animation: MinusOneAnimation): LawnchairOperationResult

    fun toggleMinusOne(context: Context, animation: MinusOneAnimation): LawnchairOperationResult

    fun refreshDesktopItems(context: Context): LawnchairOperationResult

    fun removeDesktopItem(
        context: Context,
        itemId: String,
        rememberRemoval: Boolean,
    ): LawnchairOperationResult

    fun restoreDesktopItem(context: Context, itemId: String): LawnchairOperationResult

    fun requestSetDefaultLauncher(context: Context)

    fun openHomeSettings(context: Context)

    fun openLauncherAppInfo(context: Context)
}

object LawnchairSdkRuntimeBridge {
    @Volatile
    private var installedOperationsDelegate: LawnchairOperationsDelegate = NoOpLawnchairOperationsDelegate

    val operationsDelegate: LawnchairOperationsDelegate
        get() = installedOperationsDelegate

    fun installOperationsDelegate(delegate: LawnchairOperationsDelegate) {
        installedOperationsDelegate = delegate
    }
}

private object NoOpLawnchairOperationsDelegate : LawnchairOperationsDelegate {
    override fun isDefaultLauncher(context: Context): Boolean = false

    override fun launchDesktop(context: Context) {
        logUnavailable("launchDesktop")
    }

    override fun getMinusOneState(): MinusOneState? = null

    override fun openMinusOne(
        context: Context,
        animation: MinusOneAnimation,
    ): LawnchairOperationResult {
        return unavailable("openMinusOne")
    }

    override fun closeMinusOne(
        context: Context,
        animation: MinusOneAnimation,
    ): LawnchairOperationResult {
        return unavailable("closeMinusOne")
    }

    override fun toggleMinusOne(
        context: Context,
        animation: MinusOneAnimation,
    ): LawnchairOperationResult {
        return unavailable("toggleMinusOne")
    }

    override fun refreshDesktopItems(context: Context): LawnchairOperationResult {
        return unavailable("refreshDesktopItems")
    }

    override fun removeDesktopItem(
        context: Context,
        itemId: String,
        rememberRemoval: Boolean,
    ): LawnchairOperationResult {
        return unavailable("removeDesktopItem")
    }

    override fun restoreDesktopItem(
        context: Context,
        itemId: String,
    ): LawnchairOperationResult {
        return unavailable("restoreDesktopItem")
    }

    override fun requestSetDefaultLauncher(context: Context) {
        logUnavailable("requestSetDefaultLauncher")
    }

    override fun openHomeSettings(context: Context) {
        logUnavailable("openHomeSettings")
    }

    override fun openLauncherAppInfo(context: Context) {
        logUnavailable("openLauncherAppInfo")
    }

    private fun logUnavailable(operationName: String) {
        Log.w(TAG, "Ignoring $operationName because launcher runtime is not installed")
    }

    private fun unavailable(operationName: String): LawnchairOperationResult {
        logUnavailable(operationName)
        return LawnchairOperationResult.RuntimeUnavailable
    }

    private const val TAG = "LawnchairOperations"
}

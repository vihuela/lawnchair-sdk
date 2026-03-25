package app.lawnchair.sdk.internal

import android.content.Context
import android.util.Log

interface LawnchairOperationsDelegate {
    fun isDefaultLauncher(context: Context): Boolean

    fun launchDesktop(context: Context)

    fun openMinusOneAnimated(context: Context): Boolean

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

    override fun openMinusOneAnimated(context: Context): Boolean {
        logUnavailable("openMinusOneAnimated")
        return false
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

    private const val TAG = "LawnchairOperations"
}

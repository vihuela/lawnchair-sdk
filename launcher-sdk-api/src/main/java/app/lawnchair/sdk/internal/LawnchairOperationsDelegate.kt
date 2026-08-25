package app.lawnchair.sdk.internal

import android.content.Context
import android.util.Log
import app.lawnchair.sdk.api.LauncherCommand
import app.lawnchair.sdk.api.LauncherCommandResult
import app.lawnchair.sdk.api.LauncherEvent
import app.lawnchair.sdk.api.LauncherStateSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

interface LawnchairOperationsDelegate {
    val state: StateFlow<LauncherStateSnapshot>

    val events: Flow<LauncherEvent>

    suspend fun execute(command: LauncherCommand): LauncherCommandResult

    fun isDefaultLauncher(context: Context): Boolean
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
    private val mutableState = MutableStateFlow(LauncherStateSnapshot(runtimeAvailable = false))
    private val mutableEvents = MutableSharedFlow<LauncherEvent>()

    override val state: StateFlow<LauncherStateSnapshot> = mutableState.asStateFlow()

    override val events: Flow<LauncherEvent> = mutableEvents.asSharedFlow()

    override suspend fun execute(command: LauncherCommand): LauncherCommandResult {
        logUnavailable(command::class.java.simpleName)
        return LauncherCommandResult.Failed(
            reason = app.lawnchair.sdk.api.LauncherFailureReason.RuntimeUnavailable,
        )
    }

    override fun isDefaultLauncher(context: Context): Boolean = false

    private fun logUnavailable(operationName: String) {
        Log.w(TAG, "Ignoring $operationName because launcher runtime is not installed")
    }

    private const val TAG = "LawnchairOperations"
}

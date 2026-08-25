package app.lawnchair.sdk

import android.content.Context
import app.lawnchair.sdk.api.LauncherCommand
import app.lawnchair.sdk.api.LauncherCommandResult
import app.lawnchair.sdk.api.LauncherEvent
import app.lawnchair.sdk.api.LauncherStateSnapshot
import app.lawnchair.sdk.internal.LawnchairSdkRuntimeBridge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

object LawnchairOperations {
    val state: StateFlow<LauncherStateSnapshot>
        get() = LawnchairSdkRuntimeBridge.operationsDelegate.state

    val events: Flow<LauncherEvent>
        get() = LawnchairSdkRuntimeBridge.operationsDelegate.events

    suspend fun execute(command: LauncherCommand): LauncherCommandResult {
        return LawnchairSdkRuntimeBridge.operationsDelegate.execute(command)
    }

    fun isDefaultLauncher(context: Context): Boolean {
        return LawnchairSdkRuntimeBridge.operationsDelegate.isDefaultLauncher(context)
    }
}

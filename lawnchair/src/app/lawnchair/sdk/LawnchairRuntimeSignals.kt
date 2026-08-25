package app.lawnchair.sdk

import app.lawnchair.sdk.api.DesktopVisibleSource
import app.lawnchair.sdk.api.LauncherEvent
import app.lawnchair.sdk.api.LauncherIconClickEvent
import app.lawnchair.sdk.api.LauncherStateSnapshot
import app.lawnchair.sdk.api.MinusOneState
import com.android.launcher3.BuildConfig
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal object LawnchairRuntimeSignals {
    private val mutableState = MutableStateFlow(
        LauncherStateSnapshot(
            runtimeAvailable = true,
            platformAdapterVersion = BuildConfig.LAUNCHER_PLATFORM_VERSION,
        ),
    )
    private val mutableEvents = MutableSharedFlow<LauncherEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val state = mutableState.asStateFlow()
    val events = mutableEvents.asSharedFlow()

    fun onMinusOneStateChanged(state: MinusOneState) {
        mutableState.update { it.copy(minusOne = state) }
    }

    fun onDesktopVisible(source: DesktopVisibleSource) {
        mutableEvents.tryEmit(LauncherEvent.DesktopVisible(source))
    }

    fun onLauncherIconClicked(event: LauncherIconClickEvent) {
        mutableEvents.tryEmit(LauncherEvent.LauncherIconClicked(event))
    }
}

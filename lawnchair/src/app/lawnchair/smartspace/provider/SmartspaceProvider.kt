package app.lawnchair.smartspace.provider

import android.app.Activity
import android.content.Context
import app.lawnchair.util.dropWhileBusy
import com.android.launcher3.util.MainThreadInitializedObject
import com.android.launcher3.util.SafeCloseable
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

class SmartspaceProvider private constructor(context: Context) : SafeCloseable {

    val dataSources = listOf(
        SmartspaceWidgetReader(context),
        BatteryStatusProvider(context),
        NowPlayingProvider(context),
    )

    private val state = dataSources
        .map { it.targets }
        .reduce { acc, flow -> flow.combine(acc) { a, b -> a + b } }
        .shareIn(
            MainScope(),
            SharingStarted.WhileSubscribed(),
            replay = 1,
        )
    val targets = state.map { it.targets }
    val previewTargets = state
        .map { it.targets }

    suspend fun startSetup(activity: Activity) {
        state
            .map { it.requiresSetup }
            .dropWhileBusy()
            .collect { sources ->
                sources.forEach {
                    it.startSetup(activity)
                    it.onSetupDone()
                }
            }
    }

    override fun close() {
        TODO("Not yet implemented")
    }

    companion object {
        @JvmField val INSTANCE = MainThreadInitializedObject(::SmartspaceProvider)
    }
}

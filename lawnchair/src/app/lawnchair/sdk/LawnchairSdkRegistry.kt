package app.lawnchair.sdk

import android.content.Context
import android.util.Log
import app.lawnchair.sdk.api.DesktopItemsExtension
import app.lawnchair.sdk.api.DesktopItemsSnapshot
import app.lawnchair.sdk.api.DesktopVisibleListener
import app.lawnchair.sdk.api.DesktopVisibleSource
import app.lawnchair.sdk.api.LauncherIconClickListener
import app.lawnchair.sdk.api.LawnchairSdkEntry
import app.lawnchair.sdk.api.MinusOneExtension
import app.lawnchair.sdk.api.MinusOneState

internal object LawnchairSdkRegistry {
    @Volatile private var installedDesktopItems: DesktopItemsExtension? = null

    @Volatile private var installedMinusOne: MinusOneExtension? = null

    @Volatile private var installedDesktopVisibleListener: DesktopVisibleListener? = null

    @Volatile private var installedLauncherIconClickListener: LauncherIconClickListener? = null

    val desktopItems: DesktopItemsExtension?
        get() = installedDesktopItems

    val minusOne: MinusOneExtension?
        get() = installedMinusOne

    val desktopVisibleListener: DesktopVisibleListener?
        get() = installedDesktopVisibleListener

    val launcherIconClickListener: LauncherIconClickListener?
        get() = installedLauncherIconClickListener

    val hasMinusOne: Boolean
        get() = minusOne != null

    fun install(entry: LawnchairSdkEntry) {
        installedDesktopItems = readEntryProperty("desktopItems") { entry.desktopItems }
        installedMinusOne = readEntryProperty("minusOne") { entry.minusOne }
        installedDesktopVisibleListener = readEntryProperty("desktopVisibleListener") {
            entry.desktopVisibleListener
        }
        installedLauncherIconClickListener = readEntryProperty("launcherIconClickListener") {
            entry.launcherIconClickListener
        }
    }

    fun getDesktopItemsSnapshot(context: Context): DesktopItemsSnapshot {
        val extension = desktopItems ?: return DesktopItemsSnapshot.Ready(emptyList())
        return runCatching { extension.getSnapshot(context) }
            .onFailure { Log.e(TAG, "Desktop item snapshot callback failed", it) }
            .getOrDefault(DesktopItemsSnapshot.Unavailable)
    }

    fun dispatchMinusOneState(state: MinusOneState) {
        runCatching { minusOne?.onStateChanged(state) }
            .onFailure { Log.e(TAG, "Minus-one state callback failed", it) }
    }

    fun dispatchDesktopVisible(source: DesktopVisibleSource) {
        runCatching { desktopVisibleListener?.onDesktopVisible(source) }
            .onFailure { Log.e(TAG, "Desktop visibility callback failed", it) }
    }

    private fun <T> readEntryProperty(name: String, block: () -> T?): T? {
        return runCatching(block)
            .onFailure { Log.e(TAG, "SDK entry property '$name' failed", it) }
            .getOrNull()
    }

    private const val TAG = "LawnchairSdkRegistry"
}

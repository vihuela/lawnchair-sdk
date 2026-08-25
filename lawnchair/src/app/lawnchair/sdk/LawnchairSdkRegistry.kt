package app.lawnchair.sdk

import android.util.Log
import app.lawnchair.sdk.api.DesktopItemsExtension
import app.lawnchair.sdk.api.DesktopItemsSnapshot
import app.lawnchair.sdk.api.LauncherIconClickInterceptor
import app.lawnchair.sdk.api.LawnchairSdkEntry
import app.lawnchair.sdk.api.MinusOneExtension

internal object LawnchairSdkRegistry {
    @Volatile private var installedDesktopItems: DesktopItemsExtension? = null

    @Volatile private var isDesktopItemsRegistrationHealthy = true

    @Volatile private var installedMinusOne: MinusOneExtension? = null

    @Volatile private var installedIconClickInterceptor: LauncherIconClickInterceptor? = null

    val desktopItems: DesktopItemsExtension?
        get() = installedDesktopItems

    val minusOne: MinusOneExtension?
        get() = installedMinusOne

    val iconClickInterceptor: LauncherIconClickInterceptor?
        get() = installedIconClickInterceptor

    val hasMinusOne: Boolean
        get() = minusOne != null

    fun install(entry: LawnchairSdkEntry) {
        val desktopItemsResult = runCatching { entry.desktopItems }
            .onFailure { Log.e(TAG, "SDK entry property 'desktopItems' failed", it) }
        installedDesktopItems = desktopItemsResult.getOrNull()
        isDesktopItemsRegistrationHealthy = desktopItemsResult.isSuccess
        installedMinusOne = readEntryProperty("minusOne") { entry.minusOne }
        installedIconClickInterceptor = readEntryProperty("iconClickInterceptor") {
            entry.iconClickInterceptor
        }
        Log.d(
            TAG,
            "Installed SDK entry: desktopItems=${installedDesktopItems != null}, " +
                "minusOne=${installedMinusOne != null}, " +
                "iconClickInterceptor=${installedIconClickInterceptor != null}",
        )
    }

    suspend fun getDesktopItemsSnapshot(): DesktopItemsSnapshot {
        if (!isDesktopItemsRegistrationHealthy) return DesktopItemsSnapshot.Unavailable
        val extension = desktopItems ?: return DesktopItemsSnapshot.Ready(emptyList())
        return extension.getSnapshot()
    }

    private fun <T> readEntryProperty(name: String, block: () -> T?): T? {
        return runCatching(block)
            .onFailure { Log.e(TAG, "SDK entry property '$name' failed", it) }
            .getOrNull()
    }

    private const val TAG = "LawnchairSdkRegistry"
}

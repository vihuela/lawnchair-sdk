package app.lawnchair.sdk

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import app.lawnchair.sdk.api.LawnchairSdkEntry
import app.lawnchair.sdk.api.LawnchairSdkMetadata

internal object LawnchairSdkLoader {
    private var isLoaded = false

    fun load(context: Context) {
        if (isLoaded) return
        isLoaded = true

        val entryClassName = runCatching {
            context.packageManager
                .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData
                ?.getString(LawnchairSdkMetadata.ENTRY_CLASS_NAME)
        }.getOrNull().orEmpty()

        if (entryClassName.isBlank()) return

        val entry = runCatching {
            val entryClass = Class.forName(entryClassName)
            val instance = entryClass.getDeclaredConstructor().newInstance()
            instance as? LawnchairSdkEntry
        }.onFailure {
            Log.e(TAG, "Failed to load Lawnchair SDK entry: $entryClassName", it)
        }.getOrNull()

        if (entry != null) {
            LawnchairSdkRegistry.install(entry)
        }
    }

    private const val TAG = "LawnchairSdkLoader"
}

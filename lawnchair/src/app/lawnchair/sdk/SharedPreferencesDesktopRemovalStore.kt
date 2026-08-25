package app.lawnchair.sdk

import android.content.Context
import app.lawnchair.sdk.runtime.DesktopRemovalStorePort

internal class SharedPreferencesDesktopRemovalStore(context: Context) : DesktopRemovalStorePort {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )
    private val lock = Any()

    override fun prune(activeIds: Set<String>): Set<String> {
        return synchronized(lock) {
            val current = getRemovedIds()
            val retained = current.filterTo(linkedSetOf()) { it in activeIds }
            if (retained != current) {
                preferences.edit().putStringSet(KEY_REMOVED_ITEM_IDS, retained).apply()
            }
            retained
        }
    }

    override fun setRemoved(itemId: String, removed: Boolean) {
        synchronized(lock) {
            val next = getRemovedIds().toMutableSet()
            val changed = if (removed) next.add(itemId) else next.remove(itemId)
            if (changed) preferences.edit().putStringSet(KEY_REMOVED_ITEM_IDS, next).apply()
        }
    }

    private fun getRemovedIds(): Set<String> {
        return HashSet(preferences.getStringSet(KEY_REMOVED_ITEM_IDS, emptySet()).orEmpty())
    }

    private companion object {
        const val PREFS_NAME = "lawnchair_sdk_desktop_items"
        const val KEY_REMOVED_ITEM_IDS = "removed_item_ids"
    }
}

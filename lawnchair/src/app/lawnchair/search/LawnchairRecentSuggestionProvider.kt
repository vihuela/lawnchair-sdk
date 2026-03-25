package app.lawnchair.search

import android.content.Context
import android.content.SearchRecentSuggestionsProvider
import android.content.pm.ProviderInfo

class LawnchairRecentSuggestionProvider : SearchRecentSuggestionsProvider() {
    override fun attachInfo(context: Context, info: ProviderInfo) {
        setupSuggestions(info.authority, MODE)
        super.attachInfo(context, info)
    }

    companion object {
        const val MODE = DATABASE_MODE_QUERIES

        fun authority(context: Context): String {
            return "${context.packageName}.search.LawnchairRecentSuggestionProvider"
        }
    }
}

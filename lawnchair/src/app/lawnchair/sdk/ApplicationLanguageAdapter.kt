package app.lawnchair.sdk

import android.app.Application
import android.app.LocaleManager
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import app.lawnchair.LawnchairApp
import app.lawnchair.sdk.api.LauncherCommandResult
import app.lawnchair.sdk.api.LauncherRejectReason
import app.lawnchair.sdk.runtime.ApplicationLanguageResolver
import app.lawnchair.sdk.runtime.ApplicationLanguageSelection
import java.util.Locale

/** Android adapter for the platform-owned per-application locale. */
internal object ApplicationLanguageAdapter {
    fun initialize(application: Application) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            readLegacyLanguageTag(application)?.let { applyLegacyLocale(application, it) }
        }
        LawnchairRuntimeSignals.onApplicationLanguageChanged(currentLanguageTag(application))
    }

    fun setLanguage(
        application: Application,
        requestedLanguageTag: String?,
    ): LauncherCommandResult {
        val desiredLanguageTag = when (
            val selection = ApplicationLanguageResolver.resolve(requestedLanguageTag)
        ) {
            ApplicationLanguageSelection.FollowSystem -> null
            ApplicationLanguageSelection.Invalid -> {
                return LauncherCommandResult.Rejected(LauncherRejectReason.InvalidLanguageTag)
            }
            is ApplicationLanguageSelection.Specific -> selection.languageTag
        }

        if (currentLanguageTag(application) == desiredLanguageTag) {
            return LauncherCommandResult.AlreadyInRequestedState
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            localeManager(application).applicationLocales =
                desiredLanguageTag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
        } else {
            persistLegacyLanguageTag(application, desiredLanguageTag)
            applyLegacyLocale(application, desiredLanguageTag)
            LawnchairApp.recreateActivitiesCompat()
        }

        LawnchairRuntimeSignals.onApplicationLanguageChanged(desiredLanguageTag)
        SdkDesktopItemsSynchronizer.requestRefresh(application)
        return LauncherCommandResult.Applied
    }

    private fun currentLanguageTag(application: Application): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            localeManager(application)
                .applicationLocales
                .takeUnless(LocaleList::isEmpty)
                ?.get(0)
                ?.toLanguageTag()
        } else {
            readLegacyLanguageTag(application)
        }
    }

    private fun localeManager(application: Application): LocaleManager {
        return checkNotNull(application.getSystemService(LocaleManager::class.java))
    }

    @Suppress("DEPRECATION")
    private fun applyLegacyLocale(application: Application, languageTag: String?) {
        val locales = languageTag
            ?.let(LocaleList::forLanguageTags)
            ?: Resources.getSystem().configuration.locales
        LocaleList.setDefault(locales)
        Locale.setDefault(locales[0])

        val configuration = application.resources.configuration
        configuration.setLocales(locales)
        application.resources.updateConfiguration(
            configuration,
            application.resources.displayMetrics,
        )
    }

    private fun readLegacyLanguageTag(application: Application): String? {
        return application.getSharedPreferences(PREFERENCES_NAME, Application.MODE_PRIVATE)
            .getString(KEY_LANGUAGE_TAG, null)
    }

    private fun persistLegacyLanguageTag(application: Application, languageTag: String?) {
        application.getSharedPreferences(PREFERENCES_NAME, Application.MODE_PRIVATE)
            .edit()
            .apply {
                if (languageTag == null) remove(KEY_LANGUAGE_TAG) else putString(KEY_LANGUAGE_TAG, languageTag)
            }
            .apply()
    }

    private const val PREFERENCES_NAME = "lawnchair_sdk_application_language"
    private const val KEY_LANGUAGE_TAG = "language_tag"
}

package app.lawnchair.sdk.runtime

import java.util.Locale

/** Pure validation kept outside the Android adapter so the public command is portable. */
object ApplicationLanguageResolver {
    fun resolve(languageTag: String?): ApplicationLanguageSelection {
        if (languageTag == null) return ApplicationLanguageSelection.FollowSystem

        val candidate = languageTag.trim()
        if (candidate.isEmpty() || ',' in candidate) {
            return ApplicationLanguageSelection.Invalid
        }

        val locale = Locale.forLanguageTag(candidate)
        if (locale.language.isEmpty() || locale.language == UNDEFINED_LANGUAGE) {
            return ApplicationLanguageSelection.Invalid
        }

        return ApplicationLanguageSelection.Specific(locale.toLanguageTag())
    }

    private const val UNDEFINED_LANGUAGE = "und"
}

sealed interface ApplicationLanguageSelection {
    data object FollowSystem : ApplicationLanguageSelection

    data class Specific(val languageTag: String) : ApplicationLanguageSelection

    data object Invalid : ApplicationLanguageSelection
}

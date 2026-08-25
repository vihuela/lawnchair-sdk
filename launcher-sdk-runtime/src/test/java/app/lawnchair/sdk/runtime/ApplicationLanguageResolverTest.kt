package app.lawnchair.sdk.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicationLanguageResolverTest {
    @Test
    fun nullFollowsSystem() {
        assertEquals(
            ApplicationLanguageSelection.FollowSystem,
            ApplicationLanguageResolver.resolve(null),
        )
    }

    @Test
    fun validTagsAreNormalized() {
        assertEquals(
            ApplicationLanguageSelection.Specific("zh-CN"),
            ApplicationLanguageResolver.resolve(" zh-cn "),
        )
        assertEquals(
            ApplicationLanguageSelection.Specific("en"),
            ApplicationLanguageResolver.resolve("en"),
        )
        assertEquals(
            ApplicationLanguageSelection.Specific("ja"),
            ApplicationLanguageResolver.resolve("ja"),
        )
    }

    @Test
    fun emptyInvalidAndMultipleTagsAreRejected() {
        assertEquals(ApplicationLanguageSelection.Invalid, ApplicationLanguageResolver.resolve(""))
        assertEquals(ApplicationLanguageSelection.Invalid, ApplicationLanguageResolver.resolve("_"))
        assertEquals(ApplicationLanguageSelection.Invalid, ApplicationLanguageResolver.resolve("en,ja"))
    }
}

package com.cutedog.app.daily

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.cutedog.app.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.time.LocalDate

/** Real app controls and Activity recreation; not a process-death or OS-date-change test. */
class DailyUiFlowTest {
    private val fixture = DailyDeviceFixture()
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)

    @Test fun favoriteHistoryFiltersAndUnfavoriteSurviveActivityRecreation() = captureFailure("favoriteHistory") {
        fixture.requireGrantedNotifications()
        val yesterday = DailyRepository(fixture.context) { LocalDate.now().minusDays(1) }.today()
        val today = fixture.repository.today()
        compose.onNodeWithTag("nav_0").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("daily_favorite").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("daily_favorite").performScrollTo().assertTextEquals("珍藏 ♡").performClick()
        compose.waitUntil(5_000) { fixture.repository.favorites().any { it.id == today.id } }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("daily_favorite").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("daily_favorite").performScrollTo().assertTextEquals("已珍藏 ♥")
        compose.onNodeWithTag("nav_3").performClick()
        compose.onNodeWithTag("daily_history").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(today.text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("daily_history_list").performScrollToNode(hasText(yesterday.text))
        compose.onNodeWithText(yesterday.text).assertIsDisplayed()
        compose.onNodeWithText("珍藏", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(yesterday.text).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(today.text).assertIsDisplayed()
        compose.onNodeWithText("已珍藏 ♥").performClick()
        compose.waitUntil(5_000) { fixture.repository.favorites().isEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("温柔会慢慢积攒").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("温柔会慢慢积攒").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("nav_0").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("daily_favorite").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("daily_favorite").performScrollTo().assertTextEquals("珍藏 ♡")
        assertTrue(fixture.repository.history().any { it.date == yesterday.date })
        assertFalse(fixture.repository.today().favorite)
        saveScreen("favoriteHistory", "after-unfavorite")
    }

    @Test fun dailySettingsControlsPersistEnabledHourAndQuietHoursAfterRecreation() = captureFailure("dailySettings") {
        fixture.requireGrantedNotifications()
        compose.onNodeWithTag("nav_3").performClick()
        compose.onNodeWithTag("daily_enabled").performScrollTo().assertIsOff().performClick()
        compose.waitUntil(5_000) { DailySettings(fixture.context).enabled }
        compose.onNodeWithTag("daily_hour").performScrollTo().performTextReplacement("23")
        compose.onNodeWithTag("daily_quiet_start").performScrollTo().performTextReplacement("22")
        compose.onNodeWithTag("daily_quiet_end").performScrollTo().performTextReplacement("8")
        compose.onNodeWithTag("daily_quiet_end").performImeAction()
        compose.onNodeWithTag("daily_save_time").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            val settings = DailySettings(fixture.context)
            settings.hour == 23 && settings.quietStart == 22 && settings.quietEnd == 8
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("nav_3").performClick()
        compose.onNodeWithTag("daily_hour").performScrollTo().assertTextContains("23")
        compose.onNodeWithTag("daily_quiet_start").performScrollTo().assertTextContains("22")
        compose.onNodeWithTag("daily_quiet_end").performScrollTo().assertTextContains("8")
        compose.onNodeWithTag("daily_enabled").performScrollTo().assertIsOn().performClick()
        compose.waitUntil(5_000) { !DailySettings(fixture.context).enabled }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("nav_3").performClick()
        compose.onNodeWithTag("daily_enabled").performScrollTo().assertIsOff()
        assertEquals(23, DailySettings(fixture.context).hour)
        saveScreen("dailySettings", "after-disable-and-recreate")
    }

    private fun captureFailure(name: String, block: () -> Unit) {
        try { block() }
        catch (failure: Throwable) {
            runCatching { saveScreen(name, "failure") }.onFailure { failure.addSuppressed(it) }
            throw failure
        }
    }

    private fun saveScreen(test: String, name: String) {
        // A dialog can expose multiple Compose roots; preserve the real screen before
        // any semantics query that could fail while diagnosing the original assertion.
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertNotNull("Actual device screenshot must be available", screenshot)
        try { fixture.evidenceFile(test, "$name.png").outputStream().use {
            assertTrue(screenshot!!.compress(Bitmap.CompressFormat.PNG, 100, it))
        } } finally { screenshot!!.recycle() }
        fixture.evidence(test, "$name-semantics.txt", compose.onRoot(useUnmergedTree = true).printToString())
    }
}

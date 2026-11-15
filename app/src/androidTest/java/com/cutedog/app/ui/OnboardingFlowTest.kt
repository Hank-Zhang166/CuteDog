package com.cutedog.app.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.cutedog.app.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

class OnboardingFlowTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val compose = createAndroidComposeRule<MainActivity>()
    private var notificationPermissionBefore = 0
    private var hadIntroduction = false
    private var introductionValue = false
    private val initialState = object : ExternalResource() {
        override fun before() {
            val prefs = context.getSharedPreferences("onboarding", 0)
            hadIntroduction = prefs.contains("completed"); introductionValue = prefs.getBoolean("completed", false)
            prefs.edit().putBoolean("completed", false).commit()
            if (Build.VERSION.SDK_INT >= 33) notificationPermissionBefore = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        }
        override fun after() {
            val edit = context.getSharedPreferences("onboarding", 0).edit()
            if (hadIntroduction) edit.putBoolean("completed", introductionValue) else edit.remove("completed")
            edit.commit()
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(initialState).around(compose)

    @Test fun introductionHasFiveStepsAndDoesNotAcquirePermissions() {
        compose.onNodeWithTag("onboarding").assertIsDisplayed()
        compose.onNodeWithText("1 / 5").assertExists()
        compose.onNodeWithTag("onboarding_next").performClick()
        compose.onNodeWithText("2 / 5").assertExists()
        compose.onNodeWithTag("onboarding_next").performClick()
        compose.onNodeWithText("3 / 5").assertExists()
        compose.onNodeWithTag("onboarding_next").performClick()
        compose.onNodeWithText("4 / 5").assertExists()
        compose.onNodeWithTag("onboarding_next").performClick()
        compose.onNodeWithText("5 / 5").assertExists()
        compose.onNodeWithTag("onboarding_next").performClick()
        compose.onNodeWithTag("nav_0").assertExists()
        assertTrue(context.getSharedPreferences("onboarding", 0).getBoolean("completed", false))
        if (Build.VERSION.SDK_INT >= 33) assertEquals(notificationPermissionBefore, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("onboarding").assertDoesNotExist()
    }
}

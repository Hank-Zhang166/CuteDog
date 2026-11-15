package com.cutedog.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.cutedog.app.MainActivity
import com.cutedog.app.chat.ChatRepository
import com.cutedog.app.data.ReminderRepository
import com.cutedog.app.reminder.ReminderScheduler
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.util.UUID

/**
 * Drives real Compose controls and SQLite persistence without clearing existing user data.
 * Each test uses a random fixture prefix; cleanup only removes its own reminder/memory IDs.
 * API 33+ notification permission must be supplied/restored by the disposable-device host.
 */
class ProductFlowsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefix = "UI-${UUID.randomUUID().toString().take(8)}-"
    private fun fixture(value: String) = prefix + value
    private fun reminders() = ReminderRepository(context).list().filter { it.title.startsWith(prefix) }
    private fun memories() = ChatRepository(context).memories().filter { it.text.startsWith(prefix) }
    private val compose = createAndroidComposeRule<MainActivity>()
    private var hadIntroduction = false
    private var introductionValue = false
    private val isolate = object : ExternalResource() {
        override fun before() {
            assumeTrue("Provision POST_NOTIFICATIONS on the disposable test emulator; tests do not alter device permissions.", Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            val prefs = context.getSharedPreferences("onboarding", 0)
            hadIntroduction = prefs.contains("completed"); introductionValue = prefs.getBoolean("completed", false)
            prefs.edit().putBoolean("completed", true).commit()
        }
        override fun after() {
            val repository = ReminderRepository(context)
            reminders().forEach { repository.delete(it.id); ReminderScheduler(context).cancel(it.id) }
            val chat = ChatRepository(context)
            memories().forEach { chat.deleteMemory(it.id) }
            val edit = context.getSharedPreferences("onboarding", 0).edit()
            if (hadIntroduction) edit.putBoolean("completed", introductionValue) else edit.remove("completed")
            edit.commit()
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(isolate).around(compose)

    @Test fun allFourTabsExposeTheirRealScreens() {
        compose.onNodeWithTag("nav_0").performClick()
        compose.onNodeWithTag("home_screen").assertIsDisplayed()
        compose.onNodeWithTag("nav_1").performClick()
        compose.onNodeWithTag("reminders_screen").assertIsDisplayed()
        compose.onNodeWithTag("nav_2").performClick()
        compose.onNodeWithTag("chat_input").assertIsDisplayed()
        compose.onNodeWithTag("nav_3").performClick()
        compose.onNodeWithTag("pet_settings_screen").assertIsDisplayed()
        compose.onNodeWithTag("pet_regions").performScrollTo().assertIsDisplayed()
    }

    @Test fun createSearchCompleteUndoAndDeleteReminder() {
        createReminder("给老师发邮件")
        val repository = ReminderRepository(context)
        val id = reminders().single().id
        compose.onNodeWithTag("reminder_search").performTextInput(prefix)
        compose.onNodeWithTag("reminders_list").performScrollToNode(hasTestTag("reminder_$id"))
        compose.onNodeWithTag("reminder_$id").assertExists()
        compose.onNodeWithTag("reminder_complete_$id").performClick()
        compose.waitUntil(5000) { repository.get(id)?.completed == true }
        compose.onNodeWithTag("reminders_list").performScrollToNode(hasTestTag("reminder_filter_4"))
        compose.onNodeWithTag("reminder_filter_4").performClick()
        compose.onNodeWithTag("reminders_list").performScrollToNode(hasTestTag("reminder_$id"))
        compose.onNodeWithTag("reminder_complete_$id").performClick()
        compose.waitUntil(5000) { repository.get(id)?.completed == false }
        compose.onNodeWithTag("reminders_list").performScrollToNode(hasTestTag("reminder_filter_0"))
        compose.onNodeWithTag("reminder_filter_0").performClick()
        compose.onNodeWithTag("reminders_list").performScrollToNode(hasTestTag("reminder_$id"))
        compose.onNodeWithTag("reminder_$id").performClick()
        compose.onNodeWithText("删除这件事项").performScrollTo().performClick()
        compose.onNodeWithText("删除", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { repository.get(id) == null }
    }

    @Test fun recreationPreservesEditingIdentityAndDraft() {
        createReminder("旋转前的事项")
        val repository = ReminderRepository(context)
        val id = reminders().single().id
        compose.onNodeWithTag("reminders_list").performScrollToNode(hasTestTag("reminder_$id"))
        compose.onNodeWithTag("reminder_$id").performClick()
        compose.onNodeWithTag("reminder_title").performTextReplacement(fixture("旋转后的事项"))
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("reminder_title").assertTextContains(fixture("旋转后的事项"))
        compose.onNodeWithTag("reminder_save").performClick()
        compose.waitUntil(5000) { repository.get(id)?.title == fixture("旋转后的事项") }
        assertEquals(1, reminders().size)
        assertEquals(id, reminders().single().id)
    }

    @Test fun recreationDuringNewSaveDoesNotInsertTwice() {
        compose.onNodeWithTag("nav_1").performClick()
        compose.onNodeWithTag("reminder_new").performClick()
        compose.onNodeWithTag("reminder_title").performTextInput(fixture("只保存一次"))
        compose.onNodeWithTag("reminder_save").performClick()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5000) { reminders().size == 1 }
        compose.waitForIdle()
        compose.onNodeWithTag("reminder_save").assertDoesNotExist()
        assertEquals(fixture("只保存一次"), reminders().single().title)
    }

    @Test fun createReadEditAndDeleteExplicitMemory() {
        compose.onNodeWithTag("nav_2").performClick()
        compose.onNodeWithTag("chat_memories").performClick()
        compose.onNodeWithTag("memory_input").performTextInput(fixture("我正在学习日语"))
        compose.onNodeWithTag("memory_save").performClick()
        val repository = ChatRepository(context)
        compose.waitUntil(5000) { memories().size == 1 }
        val id = memories().single().id
        compose.onNodeWithTag("memory_list").performScrollToNode(hasText(fixture("我正在学习日语")))
        compose.onNodeWithText(fixture("我正在学习日语")).assertExists()
        compose.onNodeWithTag("memory_edit_$id").performClick()
        compose.onNodeWithTag("memory_input").performTextReplacement(fixture("我正在准备日语考试"))
        compose.onNodeWithTag("memory_save").performClick()
        compose.waitUntil(5000) { memories().single().text == fixture("我正在准备日语考试") }
        compose.onNodeWithTag("memory_list").performScrollToNode(hasTestTag("memory_delete_$id"))
        compose.onNodeWithTag("memory_delete_$id").performClick()
        compose.waitUntil(5000) { memories().isEmpty() }
        assertTrue(memories().isEmpty())
    }

    @Test fun largeTextAndLandscapeKeepNavigationAndControlsReachable() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val oldScale = shell("settings get system font_scale")
        var oldOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        compose.activityRule.scenario.onActivity { oldOrientation = it.requestedOrientation }
        try {
            shell("settings put system font_scale 1.5")
            compose.waitUntil(10000) { compose.activity.resources.configuration.fontScale >= 1.45f }
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            compose.onNodeWithTag("nav_1").performClick()
            compose.onNodeWithTag("reminder_new").assertIsDisplayed().performClick()
            compose.onNodeWithTag("reminder_title").performTextInput(fixture("大字与横屏可编辑"))
            compose.onNodeWithTag("reminder_save").assertIsDisplayed().performClick()
            compose.waitUntil(5000) { reminders().any { it.title == fixture("大字与横屏可编辑") } }
            compose.onNodeWithTag("nav_3").performClick()
            compose.onNodeWithTag("pet_gestures").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("nav_2").performClick()
            compose.onNodeWithTag("chat_memories").performClick()
            compose.onNodeWithTag("memory_list").performScrollToNode(hasTestTag("memory_input"))
            compose.onNodeWithTag("memory_input").assertIsDisplayed().performTextInput(fixture("大字横屏记忆"))
            compose.onNodeWithTag("memory_list").performScrollToNode(hasTestTag("memory_save"))
            compose.onNodeWithTag("memory_save").assertIsDisplayed().performClick()
            compose.waitUntil(5000) { memories().any { it.text == fixture("大字横屏记忆") } }
            compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithTag("nav_1").performClick()
            compose.onNodeWithTag("reminders_list").performScrollToNode(hasTestTag("reminder_delivery"))
            compose.onNodeWithTag("reminder_delivery").performScrollTo().performClick()
            compose.onNodeWithTag("delivery_list").performScrollToNode(hasTestTag("delivery_retry"))
            compose.onNodeWithTag("delivery_retry").assertIsDisplayed()

        } finally {
            if (oldScale.toFloatOrNull() != null) shell("settings put system font_scale $oldScale") else shell("settings delete system font_scale")
            compose.activityRule.scenario.onActivity { it.requestedOrientation = oldOrientation }
        }
    }

    private fun createReminder(label: String) {
        val title = fixture(label)
        compose.onNodeWithTag("nav_1").performClick()
        compose.onNodeWithTag("reminder_new").performClick()
        compose.onNodeWithTag("reminder_title").performTextInput(title)
        compose.onNodeWithTag("reminder_save").performClick()
        compose.waitUntil(5000) { reminders().any { it.title == title } }
        compose.waitForIdle()
    }
}

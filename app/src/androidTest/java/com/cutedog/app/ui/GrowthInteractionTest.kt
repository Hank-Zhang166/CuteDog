package com.cutedog.app.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.cutedog.app.MainActivity
import com.cutedog.app.chat.ChatRepository
import com.cutedog.app.data.Reminder
import com.cutedog.app.data.ReminderRepository
import com.cutedog.app.pet.PetView
import com.cutedog.app.pet.behavior.GrowthStore
import com.cutedog.app.reminder.ReminderScheduler
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.util.UUID

/** Human interaction dispatch and privacy boundaries; this does not claim visual animation acceptance. */
class GrowthInteractionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val compose = createAndroidComposeRule<MainActivity>()
    private val prefix = "GrowthUI-${UUID.randomUUID()}-"
    private val fixtureReminderIds = mutableListOf<Long>()
    private val fixtureMessageIds = mutableListOf<Long>()
    private val fixtureMemoryIds = mutableListOf<Long>()
    private val growthKeys = listOf("first_met", "interactions", "last_interaction")
    private var originalGrowth: Map<String, Long?> = emptyMap()
    private var hadIntroduction = false
    private var introductionValue = false
    private val fixture = object : ExternalResource() {
        override fun before() {
            val introduction = context.getSharedPreferences("onboarding", 0)
            hadIntroduction = introduction.contains("completed")
            introductionValue = introduction.getBoolean("completed", false)
            introduction.edit().putBoolean("completed", true).commit()
            val growth = context.getSharedPreferences("pet_growth", 0)
            originalGrowth = growthKeys.associateWith { if (growth.contains(it)) growth.getLong(it, 0) else null }
        }
        override fun after() {
            val reminders = ReminderRepository(context)
            fixtureReminderIds.forEach { reminders.delete(it); ReminderScheduler(context).cancel(it) }
            val chat = ChatRepository(context)
            fixtureMessageIds.forEach(chat::deleteMessage)
            fixtureMemoryIds.forEach(chat::deleteMemory)
            val growthEdit = context.getSharedPreferences("pet_growth", 0).edit()
            originalGrowth.forEach { (key, value) -> if (value == null) growthEdit.remove(key) else growthEdit.putLong(key, value) }
            growthEdit.commit()
            val introductionEdit = context.getSharedPreferences("onboarding", 0).edit()
            if (hadIntroduction) introductionEdit.putBoolean("completed", introductionValue) else introductionEdit.remove("completed")
            introductionEdit.commit()
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)

    @Test fun homeProvidesPetSettingsWithoutAnyInteractiveDog() {
        compose.onNodeWithTag("home_pet").assertDoesNotExist()
        for (action in listOf("call", "pet", "ball", "treat", "sleep")) {
            compose.onNodeWithTag("home_interact_$action").assertDoesNotExist()
        }
        compose.runOnIdle { assertNull(findPet(compose.activity.window.decorView)) }
        compose.onNodeWithTag("home_pet_settings").performScrollTo().performClick()
        compose.onNodeWithTag("pet_settings_screen").assertExists()
    }

    @Test fun systemOrModelReactionDoesNotIncreaseHumanInteractionCount() {
        val growth = GrowthStore(context)
        val before = growth.snapshot().interactions
        compose.runOnIdle {
            val view = PetView(compose.activity)
            view.react("happy")
            view.remind()
        }
        assertEquals(before, growth.snapshot().interactions)
    }

    @Test fun confirmedGrowthResetPreservesRemindersChatsAndMemories() {
        val reminders = ReminderRepository(context)
        val reminder = Reminder(title = prefix + "约定", dueAt = System.currentTimeMillis() + 3600000)
        val reminderId = reminders.save(reminder).also(fixtureReminderIds::add)
        val chat = ChatRepository(context)
        val message = chat.add("user", prefix + "聊天记录").also { fixtureMessageIds += it.id }
        val memoryId = chat.saveMemory(text = prefix + "长期记忆").also(fixtureMemoryIds::add)
        val expectedReminder = reminders.get(reminderId)
        compose.onNodeWithTag("growth_reset").performScrollTo().performClick()
        compose.onNodeWithText("从今天重新记录相伴时光？").assertExists()
        compose.onNodeWithTag("growth_reset_confirm").performClick()
        compose.waitUntil(5000) { GrowthStore(context).snapshot().interactions == 0L }
        assertEquals(expectedReminder, reminders.get(reminderId))
        assertTrue(chat.messages().any { it.id == message.id && it.content == message.content })
        assertTrue(chat.memories().any { it.id == memoryId && it.text == prefix + "长期记忆" })
        compose.onNodeWithTag("home_pet_settings").performScrollTo().assertIsEnabled()
    }

    private fun findPet(view: View): PetView? {
        if (view is PetView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findPet(view.getChildAt(index))?.let { return it }
        return null
    }
}

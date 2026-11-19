package com.cutedog.app.ui

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.cutedog.app.MainActivity
import com.cutedog.app.data.Reminder
import com.cutedog.app.data.ReminderRepository
import com.cutedog.app.pet.PetBubbleView
import com.cutedog.app.pet.PetView
import com.cutedog.app.pet.animation.FrameClipPlayer
import com.cutedog.app.reminder.ReminderScheduler
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

class ReviewedPetDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun await(message: String, timeout: Long, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        do {
            if (condition()) return
            SystemClock.sleep(16)
        } while (SystemClock.elapsedRealtime() < until)
        fail(message)
    }

    @Test fun finalCareAndBallClipsDecodeThroughTheInstalledPlayer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val player = FrameClipPlayer(context)
        try {
            // Each tick is a playback sample. The device still performs the real asynchronous
            // AssetManager read, SHA verification and WebP decode before accepting that sample.
            for ((clip, count) in mapOf("scratch" to 83, "yawn" to 64, "groom" to 125, "play_ball" to 146)) {
                val seen = sortedSetOf<Int>()
                instrumentation.runOnMainSync { assertTrue(player.request(clip)) }
                await("Did not play all $count frames of $clip; collected indices $seen", 90_000) {
                    instrumentation.runOnMainSync {
                        val frame = player.tick(1.0 / 24)
                        assertNull(frame.error)
                        if (frame.clipId == clip && !frame.loading) {
                            val bitmap = requireNotNull(frame.bitmap)
                            assertEquals(512, bitmap.width)
                            assertEquals(512, bitmap.height)
                            seen += frame.frameIndex
                        }
                    }
                    seen.size == count
                }
                assertEquals((0 until count).toList(), seen.toList())
                Log.i("CuteDogReviewedPet", "$clip: ${seen.size} displayed frames")
            }
        } finally {
            instrumentation.runOnMainSync { player.close() }
        }
    }

    @Test fun bodyTapOnlyInteractsAndDueCheckboxCompletesTheStoredReminder() {
        val scheduler = ReminderScheduler(context)
        assumeTrue("Host must grant exact alarm access for a bounded delivery time", scheduler.canScheduleExact())
        val repo = ReminderRepository(context)
        val prefs = context.getSharedPreferences("onboarding", 0)
        val original = if (prefs.contains("completed")) prefs.getBoolean("completed", false) else null
        val title = "Dog checkbox ${UUID.randomUUID()}"
        var id = 0L
        try {
            prefs.edit().putBoolean("completed", true).commit()
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                fun inspect(check: (View) -> Boolean): Boolean {
                    var result = false
                    activity.onActivity { result = check(it.window.decorView) }
                    return result
                }
                var dog: PetView? = null
                await("Home dog did not attach", 20_000) {
                    inspect { root -> dog = descendants(root).filterIsInstance<PetView>().firstOrNull { it.isShown }; dog != null }
                }
                activity.onActivity { assertTrue(dog!!.performClick()) }
                SystemClock.sleep(550)
                assertTrue(inspect { root -> dog!!.isShown && descendants(root).none { it is PetBubbleView && it.isShown } })
                val due = System.currentTimeMillis() + 2_000
                id = repo.save(Reminder(title = title, dueAt = due))
                scheduler.schedule(requireNotNull(repo.get(id)))
                await("Due checkbox was not shown by the home dog", 25_000) {
                    inspect { root ->
                        val box = descendants(root).filterIsInstance<CheckBox>()
                            .firstOrNull { it.isShown && it.tag == "pet_bubble_complete" && it.text.toString() == title }
                        box?.performClick() == true
                    }
                }
                await("Checkbox did not persist completion", 8_000) { repo.get(id)?.completed == true }
                await("Completed reminder is still visible", 8_000) {
                    inspect { root -> descendants(root).none { it is CheckBox && it.isShown && it.text.toString() == title } }
                }
                assertTrue(repo.deliveryHistory().any { it.reminderId == id && it.token == ReminderScheduler.normalToken(due) })
            }
        } finally {
            if (id != 0L) { repo.delete(id); scheduler.cancel(id) }
            val edit = prefs.edit()
            if (original == null) edit.remove("completed") else edit.putBoolean("completed", original)
            edit.commit()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}

package com.cutedog.app.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SamoyedSpeechDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chineseAndEmojiAreRevealedProgressivelyAndHistoryIsShownInFull() {
        val speech = ReplyTyping(false)
        val first = "🐕‍🦺小耳朵竖起来啦。我把红球轻轻放在你的手边。"
        val full = first + "今天想玩什么？"
        compose.setContent {
            CuteDogTheme { ChatBubble("糯米", speech.visible, false, Modifier.testTag("live_reply")) }
        }
        lateinit var job: Job
        compose.runOnIdle {
            job = CoroutineScope(Dispatchers.Main).launch { speech.display() }
            speech.preview(first)
        }
        try {
            compose.waitUntil(5_000) { speech.visible.isNotEmpty() && speech.visible.length < first.length }
            compose.runOnUiThread {
                assertTrue(speech.visible.startsWith("🐕‍🦺"))
                speech.finish(full)
                speech.showNow()
            }
            compose.waitUntil(5_000) { job.isCompleted && speech.visible == full }
            compose.onNodeWithText(full).assertIsDisplayed()
        } finally { job.cancel() }
    }

    @Test fun reducedMotionMakesOnlyTheCompleteReplyVisible() {
        val speech = ReplyTyping(true)
        lateinit var job: Job
        compose.runOnUiThread {
            job = CoroutineScope(Dispatchers.Main).launch { speech.display() }
            speech.preview("我在")
        }
        try {
            compose.runOnIdle { assertEquals("", speech.visible); speech.finish("我在这里。") }
            compose.waitUntil(3_000) { job.isCompleted }
            assertEquals("我在这里。", speech.visible)
        } finally { job.cancel() }
    }
}

package com.cutedog.app.ui

import android.icu.text.BreakIterator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** One live reply per send. History always reads the complete, persisted message. */
internal class ReplyTyping(val reducedMotion: Boolean) {
    private data class Target(val text: String, val final: Boolean = false)
    private val updates = MutableStateFlow(Target(""))
    private var fast = false
    var visible by mutableStateOf("")
        private set
    var received by mutableStateOf(false)
        private set

    fun preview(text: String) { updates.value = Target(text) } // network thread
    fun finish(text: String) {
        received = true
        updates.value = Target(text, final = true)
    }
    fun showNow() { fast = true }

    suspend fun display(onVisible: (String) -> Unit = {}) {
        fun show(text: String) { if (visible != text) { visible = text; onVisible(text) } }
        val letters = BreakIterator.getCharacterInstance()
        var previous = ""
        var offset = 0
        while (true) {
            val target = updates.value
            val text = target.text
            if (text != previous) {
                letters.setText(text)
                previous = text
            }
            if (reducedMotion && target.final) { show(text); return }
            val next = if (offset < text.length) nextCharacter(letters, text, offset) else BreakIterator.DONE
            // Keep the last streamed grapheme until its boundary is known (e.g. an emoji with a skin tone).
            if (!reducedMotion && next != BreakIterator.DONE && (next < text.length || target.final)) {
                offset = if (fast) {
                    if (target.final) text.length else {
                        var end = next
                        var following = nextCharacter(letters, text, end)
                        while (following != BreakIterator.DONE && following < text.length) {
                            end = following
                            following = nextCharacter(letters, text, end)
                        }
                        end
                    }
                } else next
                show(text.substring(0, offset))
                if (!fast) delay(when (text[offset - 1]) {
                    '。', '！', '？', '!', '?', '\n' -> 100L
                    '，', '、', '：', ',' -> 55L
                    else -> 28L
                })
            } else {
                if (target.final) { show(text); return }
                updates.first { it != target }
            }
        }
    }
    // A newer emoji can be unassigned in Android 8's ICU tables. Join its modifiers explicitly.
    private fun nextCharacter(iterator: BreakIterator, text: String, start: Int): Int {
        var end = iterator.following(start)
        while (end in 1 until text.length) {
            val before = text.codePointBefore(end)
            val after = text.codePointAt(end)
            if (before != 0x200d && after != 0x200d && after !in 0x1f3fb..0x1f3ff &&
                after !in 0xfe00..0xfe0f && after !in 0xe0020..0xe007f) break
            end = iterator.following(end)
        }
        return end
    }

}

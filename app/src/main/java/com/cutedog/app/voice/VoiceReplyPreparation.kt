package com.cutedog.app.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Render one completed clause while the service is still streaming. Nothing is played here. */
internal class VoiceReplyPreparation(
    private val scope: CoroutineScope,
    private val render: suspend (String) -> ShortArray,
) {
    private var prefix = ""
    private var task: Deferred<ShortArray?>? = null
    private var closed = false

    @Synchronized fun preview(text: String) {
        if (closed || (prefix.isNotEmpty() && text.startsWith(prefix))) return
        task?.cancel()
        prefix = firstClause(text).orEmpty()
        task = if (prefix.isEmpty()) null else {
            val candidate = prefix
            scope.async {
                try { render(candidate) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null } // The regular playback path reports a final failure.
            }
        }
    }

    suspend fun confirmed(finalText: String): Pair<String, ShortArray>? {
        val (wording, pending) = synchronized(this) {
            if (closed || prefix.isEmpty() || !finalText.startsWith(prefix)) {
                cancel()
                return null
            }
            closed = true // Ignore late network callbacks while the committed audio is prepared.
            prefix to task
        }
        val pcm = try { pending?.await() } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        }
        return if (pcm != null && pcm.isNotEmpty()) wording to pcm else null
    }

    @Synchronized fun cancel() { closed = true; task?.cancel(); task = null }

    companion object {
        /** Keep calls such as “汪！” attached to a substantive phrase, and never split a number. */
        internal fun firstClause(text: String): String? {
            var letters = 0
            for ((i, c) in text.withIndex()) {
                if (c.isLetterOrDigit()) letters++
                if (i >= 44) return null
                if (letters < 4 || c !in "，。！？；!?;,\n") continue
                if (c == ',' && text.getOrNull(i-1)?.isDigit() == true) continue
                // For a one-clause reply the completion event usually follows its punctuation
                // immediately. Let the real-time player emit its first PCM block directly.
                if ((i+1 until text.length).none { text[it].isLetterOrDigit() }) return null
                return text.substring(0,i+1)
            }
            return null
        }
    }
}

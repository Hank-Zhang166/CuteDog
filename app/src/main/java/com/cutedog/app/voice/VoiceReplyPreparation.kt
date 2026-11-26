package com.cutedog.app.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch

internal data class PreparedVoicePrefix(val text: String, val audio: ReceiveChannel<ShortArray>)

/** Render one completed clause while the service is still streaming. Nothing is played here. */
internal class VoiceReplyPreparation(
    private val scope: CoroutineScope,
    private val render: suspend (String, (ShortArray) -> Unit) -> Unit,
) {
    private var prepared: PreparedVoicePrefix? = null
    private var producer: Job? = null
    private var closed = false

    @Synchronized fun preview(text: String) {
        if (closed || prepared?.let { text.startsWith(it.text) } == true) return
        discard()
        val clause = firstClause(text) ?: return
        // A single short clause and the renderer's maximum frame count bound total PCM.
        // Native callbacks keep advancing while the network completes and storage commits.
        val audio = Channel<ShortArray>(Channel.UNLIMITED)
        prepared = PreparedVoicePrefix(clause, audio)
        producer = scope.launch {
            try {
                render(clause) { chunk -> if (chunk.isNotEmpty()) audio.trySend(chunk).getOrThrow() }
                audio.close()
            } catch (cancelled: CancellationException) {
                audio.cancel(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                audio.close(failure)
            }
        }
        producer?.invokeOnCompletion { cause -> if (cause != null) audio.close(cause) }
    }

    @Synchronized fun confirmed(finalText: String): PreparedVoicePrefix? {
        val candidate = prepared
        if (closed || candidate == null || !finalText.startsWith(candidate.text)) {
            cancel()
            return null
        }
        closed = true
        return candidate // Playback can immediately consume even a single block already produced.
    }

    @Synchronized fun cancel() { closed = true; discard() }

    private fun discard() {
        producer?.cancel(); producer = null
        prepared?.audio?.cancel(); prepared = null
    }

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

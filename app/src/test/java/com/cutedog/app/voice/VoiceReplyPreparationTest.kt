package com.cutedog.app.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class VoiceReplyPreparationTest {
    @Test fun aMeaningfulClauseIsPreparedOnceAndUsedOnlyForTheExactSavedPrefix() = runBlocking {
        val rendered = mutableListOf<String>()
        val nextBlock = CompletableDeferred<Unit>()
        val draft = VoiceReplyPreparation(this) { text, emit ->
            rendered += text
            emit(shortArrayOf(15))
            nextBlock.await()
            emit(shortArrayOf(30))
        }
        draft.preview("汪！")
        draft.preview("汪！我叼住小球啦，")
        draft.preview("汪！我叼住小球啦，你想再玩吗？")
        yield()
        assertEquals(listOf("汪！我叼住小球啦，"),rendered)
        val result = draft.confirmed("汪！我叼住小球啦，你想再玩吗？")!!
        assertEquals(rendered.single(),result.text)
        assertArrayEquals(shortArrayOf(15),result.audio.receive())
        assertFalse(nextBlock.isCompleted)
        nextBlock.complete(Unit)
        assertArrayEquals(shortArrayOf(30),result.audio.receive())
        assertTrue(result.audio.receiveCatching().isClosed)
        draft.cancel()
    }

    @Test fun correctionFailureAndCancelledTurnsCannotReleaseSpeculativeAudio() = runBlocking {
        val stopped = CompletableDeferred<Unit>()
        val draft = VoiceReplyPreparation(this) { _,_ ->
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        }
        draft.preview("我去找小球，你")
        yield()
        assertNull(draft.confirmed("我先陪着你。"))
        stopped.await()
        val cancelled = VoiceReplyPreparation(this) { _,_ -> fail("Cancelled turn rendered audio") }
        cancelled.cancel()
        cancelled.preview("我乖乖坐下啦！")
        assertNull(cancelled.confirmed("我乖乖坐下啦！"))
        val failed = VoiceReplyPreparation(this) { _,_ -> throw IllegalStateException("synthesis failed") }
        failed.preview("我先陪着你。再去玩。")
        assertTrue(failed.confirmed("我先陪着你。再去玩。")!!.audio.receiveCatching().exceptionOrNull() is IllegalStateException)
        assertNull(VoiceReplyPreparation.firstClause("我先陪着你。"))
        assertNull(VoiceReplyPreparation.firstClause("今天有12,000颗小星星"))
        assertNull(VoiceReplyPreparation.firstClause("汪！好呀！"))
    }
}

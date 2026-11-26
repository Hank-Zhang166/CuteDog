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
        val ready = CompletableDeferred<ShortArray>()
        val draft = VoiceReplyPreparation(this) { rendered += it; ready.await() }
        draft.preview("汪！")
        draft.preview("汪！我叼住小球啦，")
        draft.preview("汪！我叼住小球啦，你想再玩吗？")
        yield()
        assertEquals(listOf("汪！我叼住小球啦，"),rendered)
        ready.complete(shortArrayOf(15,30))
        val result = draft.confirmed("汪！我叼住小球啦，你想再玩吗？")!!
        assertEquals(rendered.single(),result.first)
        assertArrayEquals(shortArrayOf(15,30),result.second)
        draft.cancel()
    }

    @Test fun correctionFailureAndCancelledTurnsCannotReleaseSpeculativeAudio() = runBlocking {
        val stopped = CompletableDeferred<Unit>()
        val draft = VoiceReplyPreparation(this) {
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        }
        draft.preview("我去找小球，你")
        yield()
        assertNull(draft.confirmed("我先陪着你。"))
        stopped.await()
        val cancelled = VoiceReplyPreparation(this) { fail("Cancelled turn rendered audio"); shortArrayOf() }
        cancelled.cancel()
        cancelled.preview("我乖乖坐下啦！")
        assertNull(cancelled.confirmed("我乖乖坐下啦！"))
        val failed = VoiceReplyPreparation(this) { throw IllegalStateException("synthesis failed") }
        failed.preview("我先陪着你。再去玩。")
        assertNull(failed.confirmed("我先陪着你。再去玩。"))
        assertNull(VoiceReplyPreparation.firstClause("我先陪着你。"))
        assertNull(VoiceReplyPreparation.firstClause("今天有12,000颗小星星"))
        assertNull(VoiceReplyPreparation.firstClause("汪！好呀！"))
    }
}

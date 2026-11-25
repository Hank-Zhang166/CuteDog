package com.cutedog.app.voice

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PetSpeechDeviceTest {
    @Test fun bundledChineseVoiceProducesPcmAndStopsAnActiveUtterance() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        PetSpeech(app).use { pet ->
            val pcm = withTimeout(120_000) { pet.renderSamples("我喜欢小狗。") }
            assertTrue("24 kHz speech duration", pcm.size in 16_000..160_000)
            val rms = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)
            assertTrue("non-silent neural audio", rms > 100)
            val dir = requireNotNull(app.getExternalFilesDir(null))
            writeWav(File(dir, "pet-speech-device.wav"), pcm)
            val greeting = withTimeout(120_000) {
                pet.renderSamples("汪！你回来啦？我刚把毛毛梳好，快来摸摸我的小脑袋呀！")
            }
            writeWav(File(dir, "pet-speech-preview.wav"), greeting)
            val playing = async { pet.speak("我喜欢小狗。".repeat(8)) }
            delay(500)
            val start = SystemClock.elapsedRealtime()
            pet.stop()
            try { withTimeout(5000) { playing.await() }; fail("Stopped speech completed normally") }
            catch (_: CancellationException) { /* speech was interrupted */ }
            assertTrue("native run and audio stop promptly", SystemClock.elapsedRealtime() - start < 5000)
        }
    }
    private fun writeWav(file: File, pcm: ShortArray) {
        val header = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(24000).putInt(48000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcm.size * 2)
        for (sample in pcm) header.putShort(sample)
        file.writeBytes(header.array())
    }

}

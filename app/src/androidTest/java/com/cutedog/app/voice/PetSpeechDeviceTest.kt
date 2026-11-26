package com.cutedog.app.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PetSpeechDeviceTest {
    @Test fun starvedTrackPlaysItsFinalShortBuffer() = runBlocking {
        val rate = 24_000
        val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(16384, minimum * 3)).build()
        try {
            val fill = ShortArray(track.bufferSizeInFrames)
            assertEquals(fill.size, track.write(fill, 0, fill.size, AudioTrack.WRITE_NON_BLOCKING))
            track.play()
            withTimeout(5000) { while (track.underrunCount == 0) delay(10) }
            // Give the mixer time to disable the starved track, as happens between neural chunks.
            delay(500)
            val tail = ShortArray(128) { (it - 64).toShort() }
            assertEquals(tail.size, track.write(tail, 0, tail.size, AudioTrack.WRITE_NON_BLOCKING))
            val target = (fill.size + tail.size).toLong()
            PetSpeech.drainSpeech(track, target)
            assertTrue("Mixer consumed the last real frame", (track.playbackHeadPosition.toLong() and 0xffffffffL) >= target)
        } finally {
            track.pause()
            track.flush()
            track.release()
        }
    }

    @Test fun bundledAVoiceSynthesizesAndStops() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("This build includes the optional legacy model", com.cutedog.app.BuildConfig.LEGACY_SPEECH)
        assumeTrue("A speech synthesis requires a 64-bit app process", android.os.Process.is64Bit())
        PetSpeech(app).use { pet ->
            val firstStart = SystemClock.elapsedRealtime()
            assertTrue("The installed APK must contain its A voice models", QwenVoicePack.ready(app))
            val pcm = withTimeout(360_000) { pet.renderSamples("我喜欢小狗。") }
            val firstMs = SystemClock.elapsedRealtime() - firstStart
            assertTrue("24 kHz speech duration", pcm.size in 7_200..192_000)
            val rms = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)
            assertTrue("non-silent neural audio", rms > 100)
            val dir = requireNotNull(app.getExternalFilesDir(null))
            writeWav(File(dir, "pet-speech-device.wav"), pcm)
            // The second sentence exercises a different sequence of synthesized phonemes.
            val nextStart = SystemClock.elapsedRealtime()
            val previewText = "我把小球叼过来了，你还想再玩一次吗？"
            val greeting = withTimeout(360_000) { pet.renderSamples(previewText) }
            val nextMs = SystemClock.elapsedRealtime() - nextStart
            assertFalse("Distinct sentences must produce distinct audio", greeting.contentEquals(pcm))
            assertTrue("second neural sentence is non-silent", greeting.any { kotlin.math.abs(it.toInt()) > 100 })
            writeWav(File(dir, "pet-speech-preview.wav"), greeting)
            // Each measured call returns one complete phrase of non-silent PCM. The first call
            // includes bundled model preparation and loading on the first run after a clean install.
            val status = File("/proc/self/status").readLines()
            fun procKiB(key: String): Long? = status.firstOrNull { it.startsWith("$key:") }
                ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()
            val timing = JSONObject().apply {
                put("sampleRateHz", 24_000)
                put("firstText", "我喜欢小狗。")
                put("firstPhrasePcmReadyMs", firstMs)
                put("firstPcmSamples", pcm.size)
                put("nextText", previewText)
                put("nextPhrasePcmReadyMs", nextMs)
                put("nextPcmSamples", greeting.size)
                put("processVmRssKiB", procKiB("VmRSS") ?: JSONObject.NULL)
                put("processVmHwmKiB", procKiB("VmHWM") ?: JSONObject.NULL)
            }
            File(dir, "pet-speech-device-timing.json").writeText(timing.toString(2))
            val playing = async { pet.speak("我喜欢小狗。".repeat(8)) }
            delay(500)
            val start = SystemClock.elapsedRealtime()
            pet.stop()
            val cancelled = withTimeout(5_000) { runCatching { playing.await() }.exceptionOrNull() }
            assertTrue("Stopped speech must cancel", cancelled is CancellationException)
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

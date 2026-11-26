package com.cutedog.app.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object RealtimePcmPlayer {
    private const val RATE = 24_000

    suspend fun play(packets: ReceiveChannel<ByteArray>) = withContext(Dispatchers.IO) {
        val first = packets.receiveCatching().getOrNull() ?: return@withContext
        val minimum = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0)
        val player = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(4800, minimum)).build()
        try { coroutineScope {
            if (Build.VERSION.SDK_INT >= 31) {
                player.startThresholdInFrames = minOf(player.bufferSizeInFrames, RATE / 12)
            }
            player.play()
            val timing = launch {
                while (player.playbackHeadPosition <= 0) delay(8)
                Log.i("CuteDogRealtimeVoice", "audio_started elapsedMs=${SystemClock.elapsedRealtime()}")
            }
            var frames = 0L
            suspend fun enqueue(pcm: ByteArray) {
                require(pcm.size % 2 == 0)
                var offset = 0
                while (offset < pcm.size) {
                    currentCoroutineContext().ensureActive()
                    val size = player.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                    check(size >= 0)
                    offset += size
                    frames += size / 2
                    if (size == 0) delay(4)
                }
            }
            try {
                enqueue(first)
                for (pcm in packets) enqueue(pcm)
                PetSpeech.drainSpeech(player, frames)
            } finally { timing.cancel() }
        } } finally {
            runCatching { player.pause(); player.flush() }
            player.release()
        }
    }
}

package com.cutedog.app.voice

import android.content.Context
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs


/** On-device A-voice. The menu and chat share one model so opening both cannot double native RAM. */
class PetSpeech(context: Context, private val onStatus: (String) -> Unit = {}) : AutoCloseable {
    private val app = context.applicationContext.also { observeMemoryPressure(it) }
    private val main = Handler(Looper.getMainLooper())
    private val reference by lazy {
        listOf("A.txt", "A.spk", "A.rvq").map { name ->
            app.assets.open("samoyed-voice/$name").use { it.readBytes() }
        }
    }
    private val lock = Any()
    @Volatile private var closed = false
    private val owners = mutableSetOf<Job>()
    private var playback: Job? = null

    private fun status(text: String) { main.post { if (!closed) onStatus(text) } }

    /** First microphone press can load the fixed A reference while the owner is talking. */
    fun prewarm() {
        if (closed || !Process.is64Bit()) return
        synchronized(warmLock) {
            if (warmTask?.isActive == true) return
            warmTask = housekeeper.launch {
                engineGate.withLock {
                    try {
                        releaseJob?.cancel()
                        ensureEngine(quiet = true)
                        if (!primed) primed = QwenNative.prime(engine, reference[2])
                    } catch (_: Exception) {
                        // A real request retries opening and reports a user-facing error.
                    } finally { scheduleIdleRelease() }
                }
            }
        }
    }

    internal suspend fun renderSamples(text: String): ShortArray {
        val rendered = ArrayList<ShortArray>()
        streamSamples(text) { rendered.add(it) }
        return flatten(rendered)
    }

    internal suspend fun streamSamples(text: String, output: (ShortArray) -> Unit) = owned(false) { job ->
        withEngine(job) { model ->
            for (phrase in parts(text)) synthesize(model, job, phrase) { pcm -> output(pcm); true }
        }
    }

    private data class Wave(val pcm: ShortArray, val readyMs: Long, val estimatedSeconds: Double)
    internal suspend fun speak(text: String, preparedPrefix: PreparedVoicePrefix? = null): Unit = owned(true) { job ->
        val line = text.trim()
        if (line.isEmpty()) return@owned
        val prefix = preparedPrefix?.takeIf { it.text.isNotBlank() && line.startsWith(it.text) }
        val letters = line.count { it.isLetterOrDigit() }
        status("小狗在准备声音…")
        val minimum = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "未找到可用的声音输出设备" }
        val player = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(16384, minimum * 3)).build()
        try {
            coroutineScope {
                // JNI runs its callback on the model worker. Never hold that worker on audio I/O.
                val queue = Channel<Wave>(Channel.UNLIMITED)
                launch(Dispatchers.Default) {
                    try {
                        var tail = line
                        var predicted = maxOf(.5, letters / 4.9)
                        var spokenLetters = 0
                        var spokenSamples = 0
                        fun completed(phrase: String, samples: Int) {
                            spokenLetters += phrase.count { it.isLetterOrDigit() }
                            spokenSamples += samples
                            if (spokenLetters < 4 || spokenSamples == 0) return
                            val seconds = spokenSamples.toDouble() / RATE
                            val pace = (spokenLetters / seconds).coerceIn(3.6, 6.2)
                            predicted = seconds + maxOf(0, letters - spokenLetters) / pace
                        }
                        if (prefix != null) {
                            var prefixSamples = 0
                            try {
                                for (pcm in prefix.audio) {
                                    prefixSamples += pcm.size
                                    queue.send(Wave(pcm, SystemClock.elapsedRealtime(), predicted))
                                }
                                if (prefixSamples > 0) {
                                    completed(prefix.text, prefixSamples)
                                    tail = line.substring(prefix.text.length).trim()
                                }
                            } catch (failed: Exception) {
                                job.ensureActive()
                                if (prefixSamples > 0) throw failed
                            }
                        }
                        if (tail.isNotEmpty()) withEngine(job) { model ->
                            for (phrase in parts(tail)) {
                                var samples = 0
                                synthesize(model, job, phrase) { pcm ->
                                    samples += pcm.size
                                    queue.trySend(Wave(pcm, SystemClock.elapsedRealtime(), predicted)).isSuccess
                                }
                                completed(phrase, samples)
                            }
                        }
                    } finally { queue.close() }
                }
                var written = 0L
                var firstVoicedWritten = false
                var playing = false
                var underrunsAtLastWrite = 0
                fun startIfReady(force: Boolean = false) {
                    if (playing || written == 0L || (!force && written < minOf(2400, player.bufferSizeInFrames / 2))) return
                    player.play()
                    playing = true
                    status("小狗在说话…")
                }
                suspend fun write(pcm: ShortArray) {
                    var offset = 0
                    while (offset < pcm.size) {
                        job.ensureActive()
                        val n = player.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                        check(n >= 0) { "小狗的声音输出中断" }
                        if (n == 0) {
                            startIfReady(force = true)
                            delay(4)
                            continue
                        }
                        if (!firstVoicedWritten && (offset until offset + n).any { abs(pcm[it].toInt()) > 140 }) {
                            firstVoicedWritten = true
                            Log.i("CuteDogVoiceTiming", "first_voiced_write elapsedMs=" + SystemClock.elapsedRealtime())
                        }
                        offset += n
                        written += n
                        startIfReady()
                        underrunsAtLastWrite = player.underrunCount
                    }
                }
                // Hold one continuous reservoir across the in-flight prefix and its suffix.
                // Once playback starts, all later chunks flow directly to the same AudioTrack.
                val waiting = ArrayList<ShortArray>()
                var held = 0
                var firstChunkMs = 0L
                var firstChunkSize = 0
                var released = false
                for (chunk in queue) {
                    job.ensureActive()
                    if (Log.isLoggable(CHUNK_LOG_TAG, Log.DEBUG)) {
                        Log.d(CHUNK_LOG_TAG, "samples=" + chunk.pcm.size + " readyMs=" + chunk.readyMs)
                    }
                    if (released) { write(chunk.pcm); continue }
                    if (waiting.isEmpty()) { firstChunkMs = chunk.readyMs; firstChunkSize = chunk.pcm.size }
                    waiting.add(chunk.pcm)
                    held += chunk.pcm.size
                    if (held <= firstChunkSize) continue
                    val producedSeconds = (held - firstChunkSize).toDouble() / RATE
                    val factor = (chunk.readyMs - firstChunkMs) / (1000.0 * producedSeconds)
                    val reserve = maxOf(.48, .15 + chunk.estimatedSeconds *
                        maxOf(0.0, 1.0 - 1.0 / maxOf(.1, factor * 1.04)))
                    if (held.toDouble() / RATE >= reserve) {
                        released = true
                        for (pcm in waiting) write(pcm)
                        waiting.clear()
                    }
                }
                for (pcm in waiting) write(pcm)
                startIfReady(force = true)
                withTimeout(6000) {
                    while ((player.playbackHeadPosition.toLong() and 0xffffffffL) < written) delay(8)
                }
                Log.i("CuteDogVoiceTiming", "playback_drained elapsedMs=" + SystemClock.elapsedRealtime() +
                    " underruns=" + player.underrunCount + " beforeLastWrite=" + underrunsAtLastWrite)
                Unit
            }
        } finally {
            runCatching { player.pause(); player.flush() }
            player.release()
        }
    }

    private fun ensureEngine(quiet: Boolean) {
        if (engine != 0L) return
        check(Process.is64Bit()) { "本机小奶音需要 64 位 Android。语音听写和文字聊天仍可使用。" }
        check(QwenVoicePack.ready(app)) { "安装包里的小奶音资源不可用，请重新安装完整的 CuteDog。" }
        if (!quiet) status("正在本机载入 A 声线…")
        engine = QwenNative.open(app.assets, QwenVoicePack.TALKER.toByteArray(), QwenVoicePack.CODEC.toByteArray())
        check(engine != 0L) { "未能载入内置小奶音，请释放一些运行内存后重试。" }
        app.noBackupFilesDir.resolve("kokoro-05d793700b0b.onnx").delete()
    }

    private suspend fun <T> owned(isPlayback: Boolean, block: suspend (Job) -> T): T = coroutineScope {
        val job = currentCoroutineContext().job
        synchronized(lock) {
            check(!closed)
            if (isPlayback) { playback?.cancel(); playback = job }
            owners.add(job)
        }
        try { withContext(Dispatchers.IO) { block(job) } }
        finally {
            synchronized(lock) { owners.remove(job); if (playback === job) playback = null }
        }
    }

    private suspend fun <T> withEngine(job: Job, block: (Long) -> T): T {
        // Waiting for a native matrix multiplication must never delay stopping playback.
        val work = housekeeper.async {
            engineGate.withLock {
                releaseJob?.cancel()
                try {
                    job.ensureActive()
                    ensureEngine(quiet = true)
                    job.ensureActive()
                    block(engine)
                } finally { scheduleIdleRelease() }
            }
        }
        try { return work.await() }
        finally { work.cancel() }
    }

    private fun synthesize(model: Long, job: Job, line: String, consume: (ShortArray) -> Boolean) {
        job.ensureActive()
        var received = false
        val sink = object : QwenNative.Output {
            override fun stopped() = !job.isActive
            override fun accept(samples: ShortArray): Boolean {
                if (stopped()) return false
                received = true
                return consume(samples)
            }
        }
        val (transcript, speaker, codes) = reference
        val pace = if (line.count { it in '\u4e00'..'\u9fff' } >= 12) 1.08f else 1f
        val rc = QwenNative.render(model, line.toByteArray(), transcript, speaker, codes,
            (6 * line.length + 95).coerceIn(96, 650), pace, sink)
        job.ensureActive()
        check(rc == 0 && received) { "本机语音合成未完成，请稍后再试。" }
        primed = true
    }

    fun stop() { synchronized(lock) { owners.toList() }.forEach { it.cancel() } }
    override fun close() {
        synchronized(lock) { closed = true }
        stop()
    }

    companion object {
        private const val RATE = 24_000
        private const val CHUNK_LOG_TAG = "CuteDogVoiceChunks"
        private val engineGate = Mutex()
        private val housekeeper = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private var engine = 0L
        private val warmLock = Any()
        private var warmTask: Job? = null
        private var primed = false
        private var epoch = 0L
        private var releaseJob: Job? = null
        private var observingMemory = false

        @Synchronized private fun observeMemoryPressure(app: Context) {
            if (observingMemory) return
            app.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onConfigurationChanged(config: Configuration) = Unit
                override fun onLowMemory() = releaseIfIdle()
                override fun onTrimMemory(level: Int) {
                    if (level in ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE..ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) releaseIfIdle()
                }
            })
            observingMemory = true
        }

        private fun releaseIfIdle() {
            housekeeper.launch {
                if (!engineGate.tryLock()) return@launch
                try { releaseJob?.cancel(); dropCachedEngine() }
                finally { engineGate.unlock() }
            }
        }

        private fun dropCachedEngine() {
            if (engine != 0L) {
                QwenNative.release(engine); engine = 0; primed = false
            }
        }

        private fun scheduleIdleRelease() {
            val lease = ++epoch
            releaseJob = housekeeper.launch {
                delay(60_000)
                engineGate.withLock {
                    if (epoch == lease) dropCachedEngine()
                }
            }
        }

        private fun parts(text: String): List<String> {
            val result = ArrayList<String>()
            var rest = text.trim()
            while (rest.length > 58) {
                val end = rest.take(58).indexOfLast { it in "。！？!?；;\n，, " }
                var cut = if (end >= 14) end + 1 else 58
                if (Character.isHighSurrogate(rest[cut - 1])) cut--
                result.add(rest.substring(0, cut).trim())
                rest = rest.substring(cut).trimStart()
            }
            if (rest.isNotEmpty()) result.add(rest)
            return result
        }

        private fun flatten(parts: List<ShortArray>): ShortArray {
            val result = ShortArray(parts.sumOf { it.size })
            var offset = 0
            for (part in parts) { part.copyInto(result, offset); offset += part.size }
            return result
        }

    }
}

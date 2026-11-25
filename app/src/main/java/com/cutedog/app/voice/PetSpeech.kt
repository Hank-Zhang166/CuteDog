package com.cutedog.app.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest

/** On-device, 24 kHz Mandarin synthesis. speak returns after the audio reaches the output device. */
class PetSpeech(context: Context, private val onStatus: (String) -> Unit = {}) : AutoCloseable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val gate = Mutex()
    private val lock = Any()
    private var active: Utterance? = null
    @Volatile private var closed = false
    private var engine: Engine? = null

    private class Utterance {
        @Volatile var cancelled = false
        private var worker: Job? = null
        private var options: OrtSession.RunOptions? = null
        private var track: AudioTrack? = null

        @Synchronized fun attach(job: Job) { worker = job; if (cancelled) job.cancel() }
        @Synchronized fun options(value: OrtSession.RunOptions?) {
            options = value
            if (cancelled && value != null) value.setTerminate(true)
        }
        @Synchronized fun track(value: AudioTrack?) {
            track = value
            if (cancelled && value != null) throw CancellationException()
        }
        @Synchronized fun cancel() {
            cancelled = true
            worker?.cancel()
            runCatching { options?.setTerminate(true) }
            runCatching { track?.pause(); track?.flush() }
        }
        fun check() { if (cancelled) throw CancellationException("播放已取消") }
    }

    private data class Engine(val session: OrtSession, val text: KokoroText, val styles: FloatArray)

    private fun status(message: String) { main.post { if (!closed) onStatus(message) } }

    suspend fun prepare(onProgress: (Long, Long) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        gate.withLock { acquire(onProgress) }
        Unit
    }

    private suspend fun acquire(progress: (Long, Long) -> Unit): Engine {
        check(!closed)
        engine?.let { return it }
        val file = modelFile(progress)
        currentCoroutineContext().ensureActive()
        val words = KokoroText(app.assets.open("dogvoice/lexicon.bin"))
        val raw = app.assets.open("dogvoice/style.bin").use { it.readBytes() }
        require(raw.size == 510 * 256 * 4)
        val styles = FloatArray(510 * 256)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(styles)
        val session = OrtSession.SessionOptions().use {
            it.setIntraOpNumThreads(2); it.setInterOpNumThreads(1)
            it.setMemoryPatternOptimization(false)
            OrtEnvironment.getEnvironment().createSession(file.absolutePath, it)
        }
        if (closed) { session.close(); throw CancellationException() }
        return Engine(session, words, styles).also { engine = it }
    }

    private suspend fun modelFile(progress: (Long, Long) -> Unit): File = copyGate.withLock {
        val destination = File(app.noBackupFilesDir, "kokoro-${MODEL_HASH.take(12)}.onnx")
        if (destination.length() == MODEL_BYTES) return@withLock destination
        status("正在本机准备小狗的声音…")
        val temp = File.createTempFile("pet-voice-", ".part", app.noBackupFilesDir)
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            var n = 0L
            app.assets.open("dogvoice/kokoro.onnx").use { source ->
                temp.outputStream().buffered().use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (closed) throw CancellationException()
                        val size = source.read(buf)
                        if (size < 0) break
                        n += size
                        check(n <= MODEL_BYTES)
                        hash.update(buf, 0, size); out.write(buf, 0, size)
                        if (n % (4 * 1024 * 1024) < size || n == MODEL_BYTES) progress(n, MODEL_BYTES)
                    }
                }
            }
            val expected = MODEL_HASH.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            check(n == MODEL_BYTES && MessageDigest.isEqual(hash.digest(), expected)) { "小狗声音资源校验失败" }
            check(temp.renameTo(destination)) { "无法在本机保存声音模型" }
            destination
        } finally { temp.delete() }
    }

    suspend fun speak(text: String) {
        runUtterance { state, token ->
            val phrases = state.text.phrases(text)
            if (phrases.isEmpty()) return@runUtterance
            val minimum = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "未找到可用的声音输出设备" }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(8192, minimum * 2)).build()
            try {
                token.track(track)
                var written = 0L
                var started = false
                suspend fun write(samples: ShortArray) {
                    var offset = 0
                    while (offset < samples.size) {
                        currentCoroutineContext().ensureActive(); token.check()
                        val count = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                        check(count >= 0) { "小狗的声音输出中断" }
                        if (count == 0) delay(4) else { offset += count; written += count }
                    }
                }
                coroutineScope {
                    val queue = Channel<Pair<ShortArray, Int>>(capacity = 1)
                    launch(Dispatchers.Default) {
                        try {
                            for ((index, phrase) in phrases.withIndex()) {
                                val pause = if (index < phrases.lastIndex) phrase.pauseMillis else 0
                                queue.send(render(state, token, phrase) to pause)
                            }
                        } finally { queue.close() }
                    }
                    for ((samples, pause) in queue) {
                        if (!started) { token.check(); track.play(); started = true }
                        write(samples)
                        if (pause > 0) write(ShortArray(pause * RATE / 1000))
                    }
                }
                withTimeout(6000) {
                    while ((track.playbackHeadPosition.toLong() and 0xffffffffL) < written) {
                        token.check(); delay(8)
                    }
                }
            } finally {
                token.track(null)
                runCatching { track.pause(); track.flush() }
                track.release()
            }
        }
    }

    /** Used by the device test to check the actual native speech samples before AudioTrack. */
    internal suspend fun renderSamples(text: String): ShortArray = runUtterance { state, token ->
        val phrases = state.text.phrases(text)
        val chunks = mutableListOf<ShortArray>()
        for ((i, phrase) in phrases.withIndex()) {
            chunks += render(state, token, phrase)
            if (i < phrases.lastIndex) chunks += ShortArray(phrase.pauseMillis * RATE / 1000)
        }
        val samples = ShortArray(chunks.sumOf { it.size })
        var offset = 0
        for (chunk in chunks) { chunk.copyInto(samples, offset); offset += chunk.size }
        samples
    }

    private suspend fun <T> runUtterance(block: suspend (Engine, Utterance) -> T): T = coroutineScope {
        val token = Utterance()
        synchronized(lock) { check(!closed); active?.cancel(); active = token }
        val work = async(Dispatchers.IO) {
            gate.withLock {
                token.check()
                val state = acquire { _, _ -> }
                token.check()
                block(state, token)
            }
        }
        token.attach(work)
        try { work.await() } finally {
            token.cancel(); work.cancel()
            synchronized(lock) { if (active === token) active = null }
        }
    }

    private fun render(state: Engine, token: Utterance, phrase: KokoroText.Phrase): ShortArray {
        token.check()
        val env = OrtEnvironment.getEnvironment()
        val count = phrase.ids.size - 2
        val embedding = FloatBuffer.wrap(state.styles, count * 256, 256).slice()
        OrtSession.RunOptions().use { opt ->
            token.options(opt)
            try {
                OnnxTensor.createTensor(env, LongBuffer.wrap(phrase.ids), longArrayOf(1, phrase.ids.size.toLong())).use { ids ->
                    OnnxTensor.createTensor(env, embedding, longArrayOf(1, 256)).use { style ->
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(phrase.speed)), longArrayOf(1)).use { speed ->
                            val inputs = mapOf("tokens" to ids, "style" to style, "speed" to speed)
                            state.session.run(inputs, setOf("audio"), opt).use { result ->
                                token.check()
                                val audio = (result[0] as OnnxTensor).floatBuffer
                                val pcm = ShortArray(audio.remaining()) { (audio.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
                                return trimPadding(pcm)
                            }
                        }
                    }
                }
            } catch (e: Exception) { token.check(); throw e }
            finally { token.options(null) }
        }
    }

    /** Keep quiet phoneme attacks/releases; remove the network's additional boundary padding. */
    private fun trimPadding(pcm: ShortArray): ShortArray {
        val window = RATE / 200
        fun audible(start: Int, end: Int): Boolean {
            var sum = 0L
            for (i in start until end) sum += pcm[i].toLong() * pcm[i]
            return sum > (end - start) * 48L * 48L
        }
        var first = 0
        while (first < pcm.size && !audible(first, minOf(first + window, pcm.size))) first += window
        if (first >= pcm.size) return shortArrayOf()
        var last = pcm.size
        while (last > first && !audible(maxOf(first, last - window), last)) last -= window
        val start = maxOf(0, first - RATE / 50)
        val end = minOf(pcm.size, last + RATE * 55 / 1000)
        val trimmed = pcm.copyOfRange(start, end)
        val fade = minOf(RATE / 250, trimmed.size / 2)
        for (i in 0 until fade) {
            trimmed[i] = (trimmed[i].toInt() * i / fade).toShort()
            val j = trimmed.lastIndex - i
            trimmed[j] = (trimmed[j].toInt() * i / fade).toShort()
        }
        return trimmed
    }

    fun stop() { synchronized(lock) { active?.cancel() } }

    override fun close() {
        synchronized(lock) { if (closed) return; closed = true; active?.cancel() }
        CoroutineScope(Dispatchers.IO).launch { gate.withLock { engine?.session?.close(); engine = null } }
    }

    companion object {
        private const val RATE = 24000
        private const val MODEL_BYTES = 114203914L
        private const val MODEL_HASH = "05d793700b0b507f760c183c0e39bdd6a97b43d32fdc7bf7b54a5179f1b11222"
        private val copyGate = Mutex()
    }
}

package com.cutedog.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** On-device A-voice. The menu and chat share one model so opening both cannot double native RAM. */
class PetSpeech(context: Context, private val onStatus: (String) -> Unit = {}) : AutoCloseable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val reference by lazy {
        listOf("A.txt", "A.spk", "A.rvq").map { name ->
            app.assets.open("samoyed-voice/$name").use { it.readBytes() }
        }
    }
    private val lock = Any()
    @Volatile private var closed = false
    private var active: Job? = null

    private fun status(text: String) { main.post { if (!closed) onStatus(text) } }

    internal suspend fun renderSamples(text: String): ShortArray = runUtterance { model, job ->
        val rendered = parts(text).map { synthesize(model, job, it) }
        flatten(rendered)
    }

    suspend fun speak(text: String) = runUtterance { model, job ->
        val phrases = parts(text)
        if (phrases.isEmpty()) return@runUtterance
        val minimum = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "未找到可用的声音输出设备" }
        val player = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(8192, minimum * 2)).build()
        try {
            coroutineScope {
                // Complete each short breath before playback. Slower phones never stutter mid-word.
                val queue = Channel<ShortArray>(1)
                launch(Dispatchers.Default) {
                    try { for (phrase in phrases) queue.send(synthesize(model, job, phrase)) }
                    finally { queue.close() }
                }
                var written = 0L
                for (pcm in queue) {
                    if (written == 0L) { job.ensureActive(); player.play(); status("小狗在说话…") }
                    var offset = 0
                    while (offset < pcm.size) {
                        job.ensureActive()
                        val n = player.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                        check(n >= 0) { "小狗的声音输出中断" }
                        if (n == 0) delay(5) else { offset += n; written += n }
                    }
                }
                withTimeout(6000) {
                    while ((player.playbackHeadPosition.toLong() and 0xffffffffL) < written) delay(8)
                }
            }
        } finally {
            runCatching { player.pause(); player.flush() }
            player.release()
        }
    }

    private suspend fun <T> runUtterance(block: suspend (Long, Job) -> T): T = coroutineScope {
        val job = currentCoroutineContext().job
        synchronized(lock) { check(!closed); active?.cancel(); active = job }
        // Independent worker keeps the native handle alive through an uninterruptible
        // matrix multiply. Cancelling this screen still immediately cancels await().
        val work = housekeeper.async {
            check(Process.is64Bit()) { "本机小奶音需要 64 位 Android。语音听写和文字聊天仍可使用。" }
            check(QwenVoicePack.ready(app)) { "安装包里的小奶音资源不可用，请重新安装完整的 CuteDog。" }
            engineGate.withLock {
                releaseJob?.cancel()
                try {
                    if (engine == 0L) {
                        status("正在本机载入 A 声线…")
                        engine = QwenNative.open(app.assets, QwenVoicePack.TALKER.toByteArray(), QwenVoicePack.CODEC.toByteArray())
                        check(engine != 0L) { "未能载入内置小奶音，请释放一些运行内存后重试。" }
                        // preview.3 kept its previous voice model outside the APK.
                        app.noBackupFilesDir.resolve("kokoro-05d793700b0b.onnx").delete()
                    }
                    job.ensureActive()
                    status("小狗在准备声音…")
                    block(engine, job)
                } finally {
                    val lease = ++epoch
                    releaseJob = housekeeper.launch {
                        delay(20_000)
                        engineGate.withLock {
                            if (epoch == lease && engine != 0L) { QwenNative.release(engine); engine = 0 }
                        }
                    }
                }
            }
        }
        try {
            work.await()
        } finally {
            work.cancel()
            synchronized(lock) { if (active === job) active = null }
        }
    }

    private fun synthesize(model: Long, job: Job, line: String): ShortArray {
        job.ensureActive()
        val chunks = ArrayList<ShortArray>()
        val sink = object : QwenNative.Output {
            override fun stopped() = !job.isActive
            override fun accept(samples: ShortArray): Boolean {
                if (stopped()) return false
                chunks.add(samples)
                return true
            }
        }
        val (transcript, speaker, codes) = reference
        val rc = QwenNative.render(model, line.toByteArray(), transcript, speaker, codes,
            (6 * line.length + 95).coerceIn(96, 650), sink)
        job.ensureActive()
        check(rc == 0 && chunks.isNotEmpty()) { "本机语音合成未完成，请稍后再试。" }
        return shape(flatten(chunks), line)
    }

    fun stop() { synchronized(lock) { active?.cancel() } }
    override fun close() { synchronized(lock) { closed = true; active?.cancel() } }

    companion object {
        private const val RATE = 24_000
        private val engineGate = Mutex()
        private val housekeeper = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private var engine = 0L
        private var epoch = 0L
        private var releaseJob: Job? = null

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

        // Use the reviewed recording's 10 ms energy windows, short breaths and pitch-preserving pace.
        private fun shape(raw: ShortArray, text: String): ShortArray {
            val window = RATE / 100
            val energy = DoubleArray((raw.size + window - 1) / window) { i ->
                val a = i * window
                val b = minOf(a + window, raw.size)
                var sum = 0.0
                for (s in a until b) sum += raw[s].toDouble() * raw[s]
                sqrt(sum / (b - a))
            }
            val peak = energy.maxOrNull() ?: error("语音为空")
            val audible = maxOf(23.0, peak * .006)
            val first = energy.indexOfFirst { it > audible }
            check(first >= 0) { "本机合成的声音为空" }
            val begin = maxOf(0, first - 5)
            val end = minOf(energy.size, energy.indexOfLast { it > audible } + 9)
            val quiet = (peak * .028).coerceIn(59.0, 393.0)
            val chunks = ArrayList<ShortArray>()
            var start = begin * window
            var i = begin
            while (i < end) {
                if (energy[i] >= quiet) { i++; continue }
                val lo = i
                while (i < end && energy[i] < quiet) i++
                val duration = (i - lo) / 100.0
                if (duration >= .44) {
                    val keep = (minOf(.40, .27 + .13 * duration) * RATE / 2).toInt()
                    chunks.add(raw.copyOfRange(start, lo * window + keep))
                    start = i * window - keep
                }
            }
            chunks.add(raw.copyOfRange(start, minOf(end * window, raw.size)))
            var pcm = flatten(chunks)
            val syllables = text.count { it in '\u4e00'..'\u9fff' }
            if (syllables >= 12) {
                val speed = (4.9f * pcm.size / (RATE * syllables)).coerceIn(1f, 1.22f)
                if (speed > 1.015f) pcm = QwenNative.tempo(pcm, speed) ?: error("无法调整小狗的语速")
            }
            val loudest = pcm.maxOfOrNull { abs(it.toInt()) } ?: error("语音为空")
            val gain = minOf(2f, 27197f / maxOf(1, loudest))
            val fade = minOf(RATE / 250, pcm.size / 2)
            for (s in pcm.indices) {
                val envelope = if (fade > 0) minOf(1f, s / fade.toFloat(), (pcm.lastIndex - s) / fade.toFloat()) else 1f
                pcm[s] = (pcm[s] * gain * envelope).roundToInt().coerceIn(-32768, 32767).toShort()
            }
            return pcm
        }
    }
}

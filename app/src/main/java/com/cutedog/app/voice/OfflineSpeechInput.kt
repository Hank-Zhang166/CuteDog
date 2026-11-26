package com.cutedog.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/** Raw audio lives in memory only, from touch-down until release or cancellation. */
internal class MicCapture private constructor(private val recorder: AudioRecord, scope: CoroutineScope,
    private val listening: OfflineSpeechInput.Listening?, private val onPcm: ((ByteArray) -> Unit)?) {
    @Volatile private var recording = true
    private val driver = Any()
    private var released = false
    private val samples = scope.async(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
        val buffer = ByteArray(if (onPcm == null) 3200 else 640)
        val audio = if (listening == null && onPcm == null) ByteArrayOutputStream() else null
        var captured = 0
        try {
            while (recording && isActive && captured < MAX_BYTES) {
                val count = recorder.read(buffer, 0, minOf(buffer.size,MAX_BYTES-captured))
                if (count > 0) {
                    captured += count
                    when {
                        listening != null -> listening.offer(buffer, count)
                        onPcm != null -> onPcm.invoke(buffer.copyOf(count))
                        else -> audio?.write(buffer, 0, count)
                    }
                }
                else if (recording) error("麦克风中断了，请松手后再试。")
            }
            audio?.toByteArray() ?: byteArrayOf()
        } finally {
            listening?.endInput()
            synchronized(driver) {
                if (!released) {
                    recording = false
                    runCatching { recorder.stop() }
                    recorder.release()
                    released = true
                }
            }
        }
    }

    fun end() {
        synchronized(driver) {
            if (recording && !released) {
                recording = false
                runCatching { recorder.stop() }
            }
        }
    }

    suspend fun finish(): ByteArray {
        end()
        return samples.await()
    }

    suspend fun finishTranscript(): String {
        end()
        samples.await() // Flush the last AudioRecord block before requesting the final transcript.
        return requireNotNull(listening).finish()
    }

    fun discard() { listening?.cancel(); end() }

    companion object {
        const val RATE = 16000
        private const val MAX_BYTES = 30 * RATE * 2
        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        fun start(context: Context, scope: CoroutineScope, transcribe: Boolean = false,
                  onPcm: ((ByteArray) -> Unit)? = null): MicCapture {
            check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "请先允许使用麦克风。"
            }
            val channel = AudioFormat.CHANNEL_IN_MONO
            val pcm = AudioFormat.ENCODING_PCM_16BIT
            val minimum = AudioRecord.getMinBufferSize(RATE, channel, pcm)
            check(minimum > 0) { "设备无法使用麦克风，请检查麦克风设置。" }
            val recorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setEncoding(pcm).setSampleRate(RATE).setChannelMask(channel).build())
                .setBufferSizeInBytes(maxOf(minimum * 2, 6400)).build()
            try {
                check(recorder.state == AudioRecord.STATE_INITIALIZED)
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                return MicCapture(recorder, scope, if (transcribe) OfflineSpeechInput.listen(context,scope) else null, onPcm)
            } catch (error: Exception) { recorder.release(); throw error }
        }
    }
}

internal object OfflineSpeechInput {
    private const val MODEL = "vosk-model-small-cn-0.22"
    // Recognition can trail recording briefly without stealing its audio thread. All native
    // model handles and recognizers are confined to this dispatcher, including idle release.
    private val decoder = Executors.newSingleThreadExecutor { job -> Thread(job,"CuteDog-mandarin-asr").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val maintenance = CoroutineScope(SupervisorJob() + decoder)
    private var model: Model? = null
    private var users = 0
    private var release: Job? = null
    private val hanSpaces = Regex("(?<=[\u3400-\u9fff])\\s+(?=[\u3400-\u9fff])")

    private suspend fun unpack(context: Context): File {
        val root = File(context.noBackupFilesDir, MODEL)
        if (File(root, ".complete").isFile) return root
        val temp = File(context.noBackupFilesDir, "$MODEL.partial")
        temp.deleteRecursively()
        check(temp.mkdirs()) { "无法准备本地语音识别，请检查剩余空间。" }
        try {
            ZipInputStream(context.assets.open("voice/$MODEL.zip").buffered()).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    require(entry.name.startsWith("$MODEL/"))
                    if (entry.isDirectory) continue
                    val file = File(temp, entry.name.removePrefix("$MODEL/"))
                    require(file.canonicalFile.toPath().startsWith(temp.canonicalFile.toPath()))
                    file.parentFile?.mkdirs()
                    file.outputStream().use { zip.copyTo(it) }
                }
            }
            check(File(temp, "am/final.mdl").isFile && File(temp, ".complete").createNewFile())
            if (root.exists()) root.deleteRecursively()
            check(temp.renameTo(root)) { "无法保存本地语音模型，请检查剩余空间。" }
            return root
        } finally { temp.deleteRecursively() }
    }

    class Listening internal constructor(private val blocks: Channel<ByteArray>, private val result: Deferred<Result<String>>) {
        private var bytes = 0

        /** One producer, maximum 30 seconds. A slow native decode never blocks AudioRecord.read. */
        fun offer(pcm: ByteArray, length: Int = pcm.size) {
            require(length in 0..pcm.size)
            val count = minOf(length, 30 * MicCapture.RATE * 2 - bytes)
            if (count <= 0) return
            bytes += count
            blocks.trySend(pcm.copyOf(count))
        }
        internal fun endInput() { blocks.close() }
        suspend fun finish(): String { endInput(); return result.await().getOrThrow() }
        fun cancel() { blocks.cancel(); result.cancel() }
    }

    fun listen(context: Context, scope: CoroutineScope): Listening {
        val blocks = Channel<ByteArray>(Channel.UNLIMITED)
        val app = context.applicationContext
        val result = scope.async(decoder) {
            try { Result.success(decode(app,blocks)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Result.failure(failure) }
            finally { blocks.cancel() }
        }
        return Listening(blocks,result)
    }

    private suspend fun decode(context: Context, blocks: Channel<ByteArray>): String {
        release?.cancel()
        val active = model ?: Model(unpack(context).absolutePath).also { model=it }
        users++
        try {
            val parts = mutableListOf<String>()
            var count = 0
            Recognizer(active,MicCapture.RATE.toFloat()).use { recognizer ->
                for (block in blocks) {
                    currentCoroutineContext().ensureActive()
                    count += block.size
                    if (recognizer.acceptWaveForm(block,block.size)) parts += JSONObject(recognizer.result).optString("text")
                }
                if (count < MicCapture.RATE / 2) return ""
                parts += JSONObject(recognizer.finalResult).optString("text")
            }
            return parts.filter(String::isNotBlank).joinToString(" ").trim().replace(hanSpaces, "")
        } finally {
            users--
            if (users == 0) release = maintenance.launch {
                delay(30_000)
                if (users == 0) { model?.close(); model = null }
            }
        }
    }

    suspend fun transcribe(context: Context, pcm: ByteArray): String = coroutineScope {
        if (pcm.size < MicCapture.RATE / 2) return@coroutineScope ""
        val session = listen(context,this)
        try {
            var offset = 0
            while (offset < pcm.size) {
                val end = minOf(offset+8000,pcm.size)
                session.offer(pcm.copyOfRange(offset,end))
                offset=end
            }
            session.finish()
        } finally { session.cancel() }
    }
}

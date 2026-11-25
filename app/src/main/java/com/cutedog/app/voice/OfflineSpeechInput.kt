package com.cutedog.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** Raw audio lives in memory only, from touch-down until release or cancellation. */
internal class MicCapture private constructor(private val recorder: AudioRecord, scope: CoroutineScope) {
    @Volatile private var recording = true
    private val driver = Any()
    private var released = false
    private val samples = scope.async(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
        val buffer = ByteArray(3200)
        val audio = ByteArrayOutputStream()
        try {
            while (recording && isActive && audio.size() < MAX_BYTES) {
                val count = recorder.read(buffer, 0, buffer.size)
                if (count > 0) audio.write(buffer, 0, count)
                else if (recording) error("麦克风中断了，请松手后再试。")
            }
            audio.toByteArray()
        } finally {
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

    companion object {
        const val RATE = 16000
        private const val MAX_BYTES = 30 * RATE * 2
        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        fun start(context: Context, scope: CoroutineScope): MicCapture {
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
                return MicCapture(recorder, scope)
            } catch (error: Exception) { recorder.release(); throw error }
        }
    }
}

internal object OfflineSpeechInput {
    private const val MODEL = "vosk-model-small-cn-0.22"
    private val modelLock = Mutex()
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

    suspend fun transcribe(context: Context, pcm: ByteArray): String = withContext(Dispatchers.IO) {
        if (pcm.size < MicCapture.RATE / 2) return@withContext ""
        modelLock.withLock {
            val location = unpack(context)
            val parts = mutableListOf<String>()
            Model(location.absolutePath).use { model ->
                Recognizer(model, MicCapture.RATE.toFloat()).use { recognizer ->
                    var offset = 0
                    val block = ByteArray(8000)
                    while (offset < pcm.size) {
                        currentCoroutineContext().ensureActive()
                        val length = minOf(block.size, pcm.size - offset)
                        pcm.copyInto(block, 0, offset, offset + length)
                        if (recognizer.acceptWaveForm(block, length)) parts += JSONObject(recognizer.result).optString("text")
                        offset += length
                    }
                    parts += JSONObject(recognizer.finalResult).optString("text")
                }
            }
            parts.filter(String::isNotBlank).joinToString(" ").trim().replace(hanSpaces, "")
        }
    }
}

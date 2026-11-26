package com.cutedog.app.voice

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.cutedog.app.voice.doubao.DoubaoRealtimeClient
import com.cutedog.app.voice.doubao.DoubaoSettings
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@SdkSuppress(minSdkVersion = 29)
class OverlayVoiceControllerDeviceTest {
    @Test fun overlayMicrophoneIsEligibleWhileLauncherIsBehindIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val audio = app.getSystemService(AudioManager::class.java)
        val windows = app.getSystemService(WindowManager::class.java)
        val settings = DoubaoSettings(app)
        assumeTrue(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        assumeTrue(Settings.canDrawOverlays(app))
        assumeTrue("Do not replace existing credentials", !settings.hasVoiceKey && !settings.hasSearchKey)
        fun voiceRecordings() = audio.activeRecordingConfigurations.filter {
            it.clientAudioSource == MediaRecorder.AudioSource.VOICE_RECOGNITION && it.clientFormat.sampleRate == MicCapture.RATE
        }
        assumeTrue("No competing microphone capture", voiceRecordings().isEmpty())
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))

        val captured = AtomicInteger()
        val status = AtomicReference("")
        val client = DoubaoRealtimeClient(WebSocket.Factory { request, listener ->
            object : WebSocket {
                override fun request() = request
                override fun queueSize() = 0L
                override fun cancel() = Unit
                override fun close(code: Int, reason: String?) = true
                override fun send(bytes: ByteString) = false
                override fun send(text: String): Boolean {
                    when (JSONObject(text).getString("type")) {
                        "session.create" -> listener.onMessage(this, """{"type":"session.created"}""")
                        "session.close" -> listener.onMessage(this, """{"type":"session.closed"}""")
                        "input_audio_buffer.append" -> captured.incrementAndGet()
                    }
                    return true
                }
            }
        })
        val controller = OverlayVoiceController(app, onStatus = status::set,
            onSetup = { error("The fixture already has a test key") }, realtimeClient = client)
        var overlay: View? = null
        try {
            settings.save("test-overlay-key", "")
            settings.microphoneUploadAccepted = true
            instrumentation.runOnMainSync {
                val params = WindowManager.LayoutParams(32, 32, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
                val fixture = View(app).apply { setBackgroundColor(Color.DKGRAY) }
                windows.addView(fixture, params)
                overlay = fixture
            }
            await("Window must enter the display") { overlay?.let { it.isAttachedToWindow && it.isShown } == true }
            instrumentation.runOnMainSync { assertTrue(controller.start()) }
            await("A foreground voice frame must reach the local fixture. Last status: ${status.get()}") {
                captured.get() >= 8 && voiceRecordings().any { !it.isClientSilenced }
            }
            instrumentation.runOnMainSync { controller.cancel() }
            await("The microphone must be released after cancel") { voiceRecordings().isEmpty() }
        } finally {
            instrumentation.runOnMainSync {
                controller.close()
                overlay?.takeIf { it.isAttachedToWindow }?.let(windows::removeViewImmediate)
            }
            settings.clear()
        }
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 9_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(30)
        }
        assertTrue(message, predicate())
    }
}

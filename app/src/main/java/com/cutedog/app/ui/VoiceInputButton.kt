package com.cutedog.app.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.view.MotionEvent
import android.widget.ImageButton
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cutedog.app.voice.VoiceConversation

@Composable
internal fun VoiceInputButton(enabled: Boolean, onStart: () -> Unit, onText: (String) -> Unit,
                              onStatus: (String) -> Unit, onSetup: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<ComponentActivity>().first()
    }
    val owner = LocalLifecycleOwner.current
    val status by rememberUpdatedState(onStatus)
    val transcript by rememberUpdatedState(onText)
    val begin by rememberUpdatedState(onStart)
    val setup by rememberUpdatedState(onSetup)
    val conversation = remember(activity) { VoiceConversation(activity, { status(it) }, { transcript(it) }, { setup() }) }
    var holding by remember { mutableStateOf(false) }
    var permissionPending by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionPending = false
        status(if (granted) "已允许使用麦克风，请再次按住说话。" else "未取得麦克风权限，可以在系统的应用权限中开启。")
    }
    fun start() {
        if (holding || permissionPending) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionPending = true
            permission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            begin()
            holding = conversation.start()
        }
    }
    fun release(submit: Boolean) {
        if (!holding) return
        holding = false
        if (submit) conversation.submit() else { conversation.cancel(); status("已取消录音。") }
    }
    DisposableEffect(owner, conversation) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { holding = false; conversation.cancel() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); conversation.close() }
    }
    AndroidView(modifier = Modifier.size(48.dp).testTag("chat_mic"), factory = { ctx ->
        ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setOnClickListener { if (holding) release(true) else start() }
            setOnTouchListener { view, event ->
                val inside = event.x >= 0 && event.y >= 0 && event.x < width && event.y < height
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { view.parent.requestDisallowInterceptTouchEvent(true); start() }
                    MotionEvent.ACTION_MOVE -> if (!inside) release(false)
                    MotionEvent.ACTION_UP -> { view.parent.requestDisallowInterceptTouchEvent(false); release(inside) }
                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> release(false)
                }
                true
            }
        }
    }, update = {
        it.isEnabled = enabled
        it.isPressed = holding
        it.contentDescription = if (holding) "松手发送，滑出取消" else "麦克风，按住说话"
    })
}

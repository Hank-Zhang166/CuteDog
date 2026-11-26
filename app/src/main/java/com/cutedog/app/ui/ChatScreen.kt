package com.cutedog.app.ui

import android.animation.ValueAnimator
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import java.time.LocalDate
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cutedog.app.chat.*
import com.cutedog.app.pet.PetSettings
import com.cutedog.app.voice.PetSpeech
import com.cutedog.app.voice.VoiceReplyPreparation
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.net.URI

@Composable
internal fun ChatScreen(onClose: (() -> Unit)? = null, voiceDraft: String? = null) {
    val context = LocalContext.current
    val repository = remember { ChatRepository(context.applicationContext) }
    val settings = remember { AiSettings(context.applicationContext) }
    val client = remember { ChatClient(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var input by rememberSaveable { mutableStateOf(voiceDraft.orEmpty()) }
    var voiceTurn by rememberSaveable { mutableStateOf(voiceDraft != null) }
    var voiceStatus by remember { mutableStateOf("") }
    var playback by remember { mutableStateOf<Job?>(null) }
    val speaker = remember { PetSpeech(context.applicationContext) { voiceStatus = it } }
    var busy by remember { mutableStateOf(false) }
    var requestJob by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(voiceDraft != null) }
    var showMemories by rememberSaveable { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var deleteId by rememberSaveable { mutableLongStateOf(0L) }
    var failedUserId by rememberSaveable { mutableLongStateOf(0L) }
    val pendingReplies by ChatClient.pendingReplies.collectAsStateWithLifecycle()
    var requestGeneration by remember { mutableIntStateOf(0) }
    var truncated by rememberSaveable { mutableStateOf(false) }
    val exportChat = rememberJsonExporter("conversation", "CuteDog-聊天-${LocalDate.now()}.json") { repository.exportConversation() }
    var speaking by remember { mutableStateOf<ReplyTyping?>(null) }
    var configured by remember { mutableStateOf(runCatching { settings.hasApiKey }.getOrDefault(false)) }
    var consentVisible by remember { mutableStateOf(false) }
    val consent = remember { context.getSharedPreferences("ai_ui_consent", 0) }
    val name = remember { PetSettings(context).name }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var preparingVoice by remember { mutableStateOf<VoiceReplyPreparation?>(null) }
    fun stopPlayback() {
        preparingVoice?.cancel(); preparingVoice = null
        playback?.cancel(); playback = null; speaker.stop()
    }
    fun readAnswer(answer: String, draft: VoiceReplyPreparation) {
        playback = scope.launch {
            try { speaker.speak(answer, draft.confirmed(answer)); voiceStatus = "" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { voiceStatus = e.message ?: "暂时无法播放语音。" }
            finally {
                draft.cancel()
                if (preparingVoice === draft) preparingVoice = null
                if (playback == coroutineContext[Job]) playback = null
            }
        }
    }
    DisposableEffect(lifecycle, speaker) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { stopPlayback(); voiceStatus = "" }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); stopPlayback(); speaker.close() }
    }
    LaunchedEffect(lifecycle, repository, busy) {
        if (busy) return@LaunchedEffect // This window owns the streaming bubble until its reply finishes.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                repository.observeMessages().collect { records ->
                    messages = records
                    failedUserId = records.lastOrNull()?.takeIf { it.role == "user" }?.id ?: 0L
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "无法读取聊天记录，请重新打开本页。" }
        }
    }
    LaunchedEffect(messages.size, busy) { if (messages.isNotEmpty()) listState.scrollToItem(if (busy) messages.size else messages.lastIndex) }
    fun send(retry: Boolean = false) {
        if (busy || (!retry && input.isBlank())) return
        if (!configured) { showSettings = true; return }
        if (!consent.getBoolean("remote_chat", false)) { consentVisible = true; return }
        val text = input.trim()
        val aloud = if (retry) messages.firstOrNull { it.id == failedUserId }?.voice == true else voiceTurn
        voiceTurn = false; stopPlayback(); voiceStatus = ""
        val draft = if (aloud) VoiceReplyPreparation(scope, speaker::streamSamples).also {
            speaker.prewarm(); preparingVoice = it
        } else null
        input = ""; busy = true; error = null
        requestGeneration++
        val generation = requestGeneration
        val speech = ReplyTyping(!ValueAnimator.areAnimatorsEnabled() ||
            context.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled)
        speaking = speech
        requestJob = scope.launch {
            val typing = launch { speech.display() }
            var passedToPlayer = false
            try {
                val target = if (!retry) withContext(Dispatchers.IO) { repository.add("user", text, aloud) }.id else failedUserId
                require(target > 0) { "没有可以重试的消息，请重新发送。" }
                failedUserId = target
                messages = withContext(Dispatchers.IO) { repository.messages() }
                val reply = client.replyAndSave(repository, target) { textSoFar ->
                    speech.preview(textSoFar)
                    draft?.preview(textSoFar)
                } ?: return@launch
                currentCoroutineContext().ensureActive()
                if (generation != requestGeneration) return@launch
                reply.intent?.let { com.cutedog.app.pet.PetEvents.modelAction(context, it) }
                failedUserId = 0L
                truncated = reply.contextTruncated
                speech.finish(reply.text)
                if (draft != null && preparingVoice === draft && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    passedToPlayer = true
                    readAnswer(reply.text, draft)
                }
                typing.join()
                messages = withContext(Dispatchers.IO) { repository.messages() }
            } catch (cancelled: CancellationException) { error = "这次请求已取消。你可以稍后重试。"; throw cancelled }
            catch (e: Exception) { error = e.message ?: "暂时没有连接上，请稍后重试。" }
            finally {
                if (!passedToPlayer) {
                    draft?.cancel()
                    if (preparingVoice === draft) preparingVoice = null
                }
                typing.cancel(); speaking = null; busy = false; requestJob = null
            }
        }
    }
    Column(Modifier.fillMaxSize().imePadding().testTag("chat_screen")) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            PawMark(Modifier.size(43.dp))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("和$name 聊聊", style = MaterialTheme.typography.titleLarge)
                Text(if (busy) "耳朵竖起来啦…" else if (configured) "你的萨摩耶伙伴" else "连接模型后就能聊天",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { showSettings = true }) { Text("设置") }
            if (onClose != null) TextButton(onClick = onClose) { Text("收起聊天") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { showMemories = true }, modifier = Modifier.testTag("chat_memories")) { Text("记忆") }
            TextButton(onClick = exportChat, enabled = messages.isNotEmpty()) { Text("导出") }
            TextButton(onClick = { clearConfirm = true }, enabled = messages.isNotEmpty()) { Text("清空聊天") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("chat_messages"), state = listState, contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (messages.isEmpty() && !busy) item {
                SoftCard(Modifier.fillMaxWidth()) {
                    Text("和${name}打个招呼", style = MaterialTheme.typography.titleLarge)
                    Text(if (configured) "今天有什么想跟小狗分享的？" else "连上你自己的模型服务，$name 就能读到你写的话。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!configured) FilledTonalButton(onClick = { showSettings = true }) { Text("连接模型服务") }
                }
            }
            items(messages, key = { it.id }) { item ->
                val user = item.role == "user"
                val speakerName = if (user) "你" else name
                val label = when {
                    !item.voice -> speakerName
                    user -> "$speakerName · 语音输入"
                    else -> "$speakerName · 语音回复"
                }
                ChatBubble(label, item.content, user,
                    onDelete = { deleteId = item.id }, deleteTag = "chat_delete_${item.id}")
            }
            speaking?.let { speech ->
                item(key = "speaking") {
                    var previousHeight by remember { mutableIntStateOf(0) }
                    Column(Modifier.fillMaxWidth().onSizeChanged { size ->
                        val growth = size.height - previousHeight
                        val view = listState.layoutInfo
                        val last = view.visibleItemsInfo.lastOrNull()
                        val nearBottom = last?.key == "speaking" && last.offset + last.size <= view.viewportEndOffset + 60
                        if (previousHeight > 0 && growth > 0 && nearBottom && !listState.isScrollInProgress)
                            scope.launch { listState.scrollBy(growth.toFloat()) }
                        previousHeight = size.height
                    }) {
                        if (speech.visible.isNotEmpty()) ChatBubble(name, speech.visible, false, Modifier.testTag("chat_speaking"))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (speech.visible.isEmpty()) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Text(" 等小狗回话…", style = MaterialTheme.typography.labelLarge)
                            } else if (!speech.reducedMotion) {
                                TextButton(onClick = { speech.showNow() }) { Text("跳过逐字显示") }
                            }
                            if (!speech.received) TextButton(onClick = { requestJob?.cancel() }) { Text("取消") }
                        }
                    }
                }
            }
            if (truncated) item { Text("本次只使用最近上下文，旧记录仍在本机。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (error != null) item {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(error!!, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { send(true) }, enabled = !busy && failedUserId !in pendingReplies && messages.any { it.id == failedUserId && it.role == "user" }) { Text("重试上一条") }
                    }
                }
            } else if (!busy && failedUserId > 0 && failedUserId !in pendingReplies && messages.any { it.id == failedUserId }) item {
                TextButton(onClick = { send(true) }) { Text("继续请求上一条回复") }
            }
        }
        if (voiceStatus.isNotBlank() || playback != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(voiceStatus, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            if (playback != null) TextButton(onClick = { stopPlayback(); voiceStatus = "" }) { Text("停止朗读") }
        }
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                VoiceInputButton(!busy, onStart = { stopPlayback() }, onText = {
                    input = it; voiceTurn = true; voiceStatus = "听写完成"; send()
                }, onStatus = { voiceStatus = it })
                OutlinedTextField(input, { input = it.take(8000) }, modifier = Modifier.weight(1f).testTag("chat_input"), placeholder = { Text("说给${name}听…") }, maxLines = 5, shape = RoundedCornerShape(23.dp), enabled = !busy)
                Button(onClick = { send() }, enabled = input.isNotBlank() && !busy, modifier = Modifier.heightIn(min = 54.dp).testTag("chat_send"), shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(horizontal = 18.dp)) { Text("发送") }
            }
        }
    }
    if (showSettings) AiSettingsDialog(onDismiss = { showSettings = false; configured = runCatching { settings.hasApiKey }.getOrDefault(false) })
    if (showMemories) MemoryDialog { showMemories = false }
    if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false }, title = { Text("清空本机聊天记录？") }, text = { Text("此操作无法撤销。长期记忆单独管理；已经发送给服务商的数据不在本机控制范围内。") }, confirmButton = { TextButton(onClick = { scope.launch { try { requestJob?.cancelAndJoin(); requestGeneration++; withContext(Dispatchers.IO) { repository.clear() }; messages = emptyList(); error = null; failedUserId = 0; truncated = false } catch (e: Exception) { error = "清空失败，请重试。" } }; clearConfirm = false }) { Text("清空") } }, dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("取消") } })
    if (deleteId > 0) AlertDialog(onDismissRequest = { deleteId = 0 }, title = { Text("删除这条消息？") }, text = { Text("将删除本机记录，并取消正在进行的回复。已经发送给服务商的数据无法从这里撤回。") }, confirmButton = { TextButton(onClick = { val target = deleteId; deleteId = 0; scope.launch { try { requestJob?.cancelAndJoin(); requestGeneration++; withContext(Dispatchers.IO) { repository.deleteMessage(target) }; messages = withContext(Dispatchers.IO) { repository.messages() }; if (failedUserId == target) failedUserId = 0; error = null } catch (e: Exception) { error = "删除失败，请重试。" } } }) { Text("删除消息") } }, dismissButton = { TextButton(onClick = { deleteId = 0 }) { Text("取消") } })
    if (consentVisible) AlertDialog(onDismissRequest = { consentVisible = false }, title = { Text("开始联网对话") }, text = { Text("发送后，你的当前消息与近期对话将交给设置中的模型服务商处理。API Key 仅用于该地址的身份验证。只有你开启长期记忆时，已保存的记忆才会一起发送。请确认该服务的隐私政策和计费方式。") }, confirmButton = { TextButton(onClick = { consent.edit().putBoolean("remote_chat", true).apply(); consentVisible = false; send(messages.lastOrNull()?.role == "user" && input.isBlank()) }) { Text("同意并继续") } }, dismissButton = { TextButton(onClick = { consentVisible = false }) { Text("暂不发送") } })
}

@Composable
internal fun AiSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AiSettings(context.applicationContext) }
    var endpoint by remember { mutableStateOf(settings.endpoint) }
    var model by remember { mutableStateOf(settings.model) }
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(runCatching { settings.hasApiKey }.getOrDefault(false)) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 16.dp), shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("连接你的模型", style = MaterialTheme.typography.headlineMedium)
                Text("支持 OpenAI 兼容的对话服务。DeepSeek 地址可填写 https://api.deepseek.com，模型使用你指定的 deepseek-flash。需要你自己的 API Key 和可用额度。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("HTTPS 接口根地址（/v1 按服务商要求）") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp))
                OutlinedTextField(model, { model = it }, label = { Text("模型名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp))
                OutlinedTextField(key, { key = it }, label = { Text(if (hasKey) "新 API Key（留空保留已有密钥）" else "API Key") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), singleLine = true, shape = RoundedCornerShape(16.dp))
                Text(if (hasKey) "设备上已保存加密密钥。" else "还未保存密钥。", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("密钥通过 Android Keystore 加密后保存在本机。接口地址必须可信；消息和密钥会发送给你填写的服务。更换服务后请确认服务商条款。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = {
                    try {
                        val uri = URI(endpoint.trim())
                        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "请输入有效的 HTTPS 根地址，不要包含密码、查询参数或片段。" }
                        require(model.isNotBlank()) { "请填写服务商支持的模型名称。" }
                        val previousHost = runCatching { URI(settings.endpoint).host }.getOrNull()
                        require(!hasKey || key.isNotBlank() || previousHost.equals(uri.host, ignoreCase = true)) { "更换模型服务域名时，请重新输入该服务的 API Key，或先清除旧密钥。" }
                        if (settings.endpoint != endpoint.trim()) context.getSharedPreferences("ai_ui_consent", 0).edit().putBoolean("remote_chat", false).apply()
                        settings.endpoint = endpoint.trim().trimEnd('/')
                        settings.model = model.trim()
                        if (key.isNotBlank()) settings.apiKey = key.trim()
                        onDismiss()
                    } catch (e: Exception) { error = e.message ?: "无法保存配置。" }
                }, modifier = Modifier.fillMaxWidth()) { Text("保存配置") }
                if (hasKey) OutlinedButton(onClick = { runCatching { settings.apiKey = "" }.onSuccess { hasKey = false; key = "" }.onFailure { error = "无法清除密钥。" } }, modifier = Modifier.fillMaxWidth()) { Text("清除本机 API Key") }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
            }
        }
    }
}

@Composable
internal fun MemoryDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { ChatRepository(context.applicationContext) }
    val settings = remember { AiSettings(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val exportMemories = rememberJsonExporter("memories", "CuteDog-记忆-${LocalDate.now()}.json") { repository.exportMemories() }
    var memories by remember { mutableStateOf<List<Memory>>(emptyList()) }
    var enabled by remember { mutableStateOf(settings.memoryEnabled) }
    var text by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableLongStateOf(0) }
    var confirmEnable by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { try { memories = withContext(Dispatchers.IO) { repository.memories() } } catch (e: Exception) { error = "无法读取记忆，请重新打开后重试。" } }
    fun mutate(onSuccess: () -> Unit = {}, block: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { block() }; memories = withContext(Dispatchers.IO) { repository.memories() }; error = null; onSuccess() }
            catch (e: Exception) { error = "记忆保存失败，请重试。" }
            finally { busy = false }
        }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight(.93f).padding(horizontal = 12.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(22.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("只记住你愿意的事", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") }
                }
                // The description, editor, memories and footer share one scroll area.
                // Large text and landscape cannot pin the input/actions below the window.
                LazyColumn(Modifier.weight(1f).testTag("memory_list"), state = listState, verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 10.dp, bottom = 12.dp)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("记忆由你亲自添加和编辑，不会从聊天中自动提取。关闭后保留在本机，但不会用于后续模型请求。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("允许对话使用长期记忆", modifier = Modifier.weight(1f))
                                Switch(enabled, { if (it) confirmEnable = true else { settings.memoryEnabled = false; enabled = false } })
                            }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(text, { text = it.take(2000) }, label = { Text(if (editingId == 0L) "添加一条记忆" else "修改这条记忆") }, modifier = Modifier.fillMaxWidth().testTag("memory_input"), maxLines = 5, enabled = !busy, shape = RoundedCornerShape(16.dp))
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { val value = text.trim(); val id = editingId; mutate(onSuccess = { text = ""; editingId = 0 }) { repository.saveMemory(id, value) } }, enabled = text.isNotBlank() && !busy, modifier = Modifier.weight(1f).testTag("memory_save")) { Text(if (busy) "正在保存…" else "保存记忆") }
                                if (editingId != 0L) TextButton(onClick = { editingId = 0; text = "" }, enabled = !busy) { Text("取消编辑") }
                            }
                        }
                    }
                    item { Text("已记住的事 · ${memories.size}", style = MaterialTheme.typography.titleMedium) }
                    if (memories.isEmpty()) item { EmptyNote("还没有长期记忆", "例如：我正在学习日语。只记录你愿意交给模型服务的信息。") }
                    items(memories, key = { it.id }) { memory ->
                        SoftCard(Modifier.fillMaxWidth()) {
                            Text(memory.text)
                            Row {
                                TextButton(onClick = { editingId = memory.id; text = memory.text; scope.launch { listState.animateScrollToItem(1) } }, enabled = !busy, modifier = Modifier.testTag("memory_edit_${memory.id}")) { Text("编辑") }
                                TextButton(onClick = { mutate(onSuccess = { if (editingId == memory.id) { editingId = 0; text = "" } }) { repository.deleteMemory(memory.id) } }, enabled = !busy, modifier = Modifier.testTag("memory_delete_${memory.id}")) { Text("删除") }
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = exportMemories, enabled = !busy) { Text("导出记忆") }
                            TextButton(onClick = { confirmClear = true }, enabled = memories.isNotEmpty() && !busy) { Text("清空全部") }
                        }
                    }
                }
            }
        }
    }
    if (confirmEnable) AlertDialog(onDismissRequest = { confirmEnable = false }, title = { Text("允许发送记忆给模型？") }, text = { Text("开启后，这里保存的记忆会随聊天请求发送给你配置的服务商。请避免保存身份证、支付信息等敏感内容。你可以随时关闭、修改和删除。") }, confirmButton = { TextButton(onClick = { settings.memoryEnabled = true; enabled = true; confirmEnable = false }) { Text("允许") } }, dismissButton = { TextButton(onClick = { confirmEnable = false }) { Text("取消") } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清空所有长期记忆？") }, text = { Text("这会删除本机的记忆，无法撤销。") }, confirmButton = { TextButton(onClick = { mutate(onSuccess = { text = ""; editingId = 0 }) { repository.clearMemories() }; confirmClear = false }) { Text("清空") } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}

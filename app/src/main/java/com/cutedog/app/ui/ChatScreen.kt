package com.cutedog.app.ui

import android.content.Intent
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cutedog.app.chat.*
import com.cutedog.app.pet.PetSettings
import com.cutedog.app.pet.PetView
import kotlinx.coroutines.*
import java.net.URI

@Composable
internal fun ChatScreen() {
    val context = LocalContext.current
    val repository = remember { ChatRepository(context.applicationContext) }
    val settings = remember { AiSettings(context.applicationContext) }
    val client = remember { ChatClient(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var input by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var requestJob by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showMemories by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var petView by remember { mutableStateOf<PetView?>(null) }
    var configured by remember { mutableStateOf(runCatching { settings.hasApiKey }.getOrDefault(false)) }
    var consentVisible by remember { mutableStateOf(false) }
    val consent = remember { context.getSharedPreferences("ai_ui_consent", 0) }
    val name = remember { PetSettings(context).name }
    LaunchedEffect(Unit) { messages = withContext(Dispatchers.IO) { repository.messages() } }
    LaunchedEffect(messages.size, busy) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
    fun send(retry: Boolean = false) {
        if (busy || (!retry && input.isBlank())) return
        if (!configured) { showSettings = true; return }
        if (!consent.getBoolean("remote_chat", false)) { consentVisible = true; return }
        val text = input.trim()
        input = ""; busy = true; error = null
        requestJob = scope.launch {
            try {
                if (!retry) withContext(Dispatchers.IO) { repository.add("user", text) }
                messages = withContext(Dispatchers.IO) { repository.messages() }
                val reply = client.reply(messages)
                withContext(Dispatchers.IO) { repository.add("assistant", reply.text) }
                messages = withContext(Dispatchers.IO) { repository.messages() }
                reply.intent?.let { petView?.react(it) }
            } catch (cancelled: CancellationException) { error = "这次请求已取消。你可以稍后重试。"; throw cancelled }
            catch (e: Exception) { error = e.message ?: "暂时没有连接上，请稍后重试。" }
            finally { busy = false; requestJob = null }
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AndroidView(factory = { PetView(it).also { pet -> petView = pet } }, modifier = Modifier.size(70.dp), update = { it.contentDescription = "$name 的表情" })
            Column(Modifier.weight(1f)) {
                Text("和$name 聊聊", style = MaterialTheme.typography.titleLarge)
                Text(if (busy) "正在认真听你说…" else if (configured) "连接你选择的模型服务" else "配置模型，开启真正的对话", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { showSettings = true }) { Text("设置") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { showMemories = true }) { Text("记忆") }
            TextButton(onClick = { clearConfirm = true }, enabled = messages.isNotEmpty() && !busy) { Text("清空聊天") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (messages.isEmpty()) item {
                SoftCard(Modifier.fillMaxWidth()) {
                    Text("给心事一个柔软的落脚点", style = MaterialTheme.typography.titleLarge)
                    Text("开心的瞬间、没想明白的小事，或只是今天有点累，都可以从这里说起。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider()
                    Text("联网对话由你配置的模型提供。尚未连接时，备忘录、每日一句和桌宠仍可离线使用。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!configured) FilledTonalButton(onClick = { showSettings = true }) { Text("连接模型服务") }
                }
            }
            items(messages, key = { it.id }) { item ->
                val user = item.role == "user"
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
                    Text(if (user) "你" else name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                    Surface(shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = if (user) 5.dp else 20.dp, bottomStart = if (user) 20.dp else 5.dp), color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, modifier = Modifier.widthIn(max = 560.dp)) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(item.content, modifier = Modifier.padding(17.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            if (busy) item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("正在等待模型回复", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { requestJob?.cancel() }) { Text("取消") }
                }
            }
            if (error != null) item {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(error!!, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { send(true) }, enabled = !busy && messages.lastOrNull()?.role == "user") { Text("重试上一条") }
                    }
                }
            } else if (!busy && messages.lastOrNull()?.role == "user") item {
                TextButton(onClick = { send(true) }) { Text("继续请求上一条回复") }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(input, { input = it.take(8000) }, modifier = Modifier.weight(1f), placeholder = { Text("今天想和我说什么？") }, maxLines = 5, shape = RoundedCornerShape(23.dp), enabled = !busy)
                Button(onClick = { send() }, enabled = input.isNotBlank() && !busy, modifier = Modifier.heightIn(min = 54.dp), shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(horizontal = 18.dp)) { Text("发送") }
            }
        }
    }
    if (showSettings) AiSettingsDialog(onDismiss = { showSettings = false; configured = runCatching { settings.hasApiKey }.getOrDefault(false) })
    if (showMemories) MemoryDialog { showMemories = false }
    if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false }, title = { Text("清空本机聊天记录？") }, text = { Text("此操作无法撤销。长期记忆单独管理；已经发送给服务商的数据不在本机控制范围内。") }, confirmButton = { TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { repository.clear() }; messages = emptyList(); error = null }; clearConfirm = false }) { Text("清空") } }, dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("取消") } })
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
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 16.dp), shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("连接你的模型", style = MaterialTheme.typography.headlineMedium)
                Text("支持 OpenAI 兼容的 Chat Completions 接口。需要你自己的账号、API Key 和可用额度；应用不内置开发者密钥或离线语言模型。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("HTTPS 接口根地址（包含 /v1）") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp))
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
    var memories by remember { mutableStateOf<List<Memory>>(emptyList()) }
    var enabled by remember { mutableStateOf(settings.memoryEnabled) }
    var text by rememberSaveable { mutableStateOf("") }
    var editingId by remember { mutableLongStateOf(0) }
    var confirmEnable by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { memories = withContext(Dispatchers.IO) { repository.memories() } }
    fun mutate(block: () -> Unit) {
        scope.launch {
            try { withContext(Dispatchers.IO) { block() }; memories = withContext(Dispatchers.IO) { repository.memories() }; error = null }
            catch (e: Exception) { error = "记忆保存失败，请重试。" }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.93f).padding(horizontal = 12.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("只记住你愿意的事", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); TextButton(onClick = onDismiss) { Text("关闭") } }
                Text("记忆由你亲自添加和编辑，不会从聊天中自动提取。关闭后保留在本机，但不会用于后续模型请求。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("允许对话使用长期记忆", modifier = Modifier.weight(1f)); Switch(enabled, { if (it) confirmEnable = true else { settings.memoryEnabled = false; enabled = false } })
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (memories.isEmpty()) item { EmptyNote("还没有长期记忆", "例如：我正在学习日语。只记录你愿意交给模型服务的信息。") }
                    items(memories, key = { it.id }) { memory ->
                        SoftCard(Modifier.fillMaxWidth()) {
                            Text(memory.text)
                            Row { TextButton(onClick = { editingId = memory.id; text = memory.text }) { Text("编辑") }; TextButton(onClick = { mutate { repository.deleteMemory(memory.id) } }) { Text("删除") } }
                        }
                    }
                }
                OutlinedTextField(text, { text = it.take(1000) }, label = { Text(if (editingId == 0L) "添加一条记忆" else "修改这条记忆") }, modifier = Modifier.fillMaxWidth(), maxLines = 4, shape = RoundedCornerShape(16.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { val value = text.trim(); val id = editingId; mutate { repository.saveMemory(id, value) }; text = ""; editingId = 0 }, enabled = text.isNotBlank(), modifier = Modifier.weight(1f)) { Text("保存记忆") }
                    if (editingId != 0L) TextButton(onClick = { editingId = 0; text = "" }) { Text("取消编辑") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { scope.launch { val content = withContext(Dispatchers.IO) { repository.exportMemories() }; context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "application/json"; putExtra(Intent.EXTRA_TEXT, content) }, "导出我的记忆")) } }) { Text("导出记忆") }
                    TextButton(onClick = { confirmClear = true }, enabled = memories.isNotEmpty()) { Text("清空全部") }
                }
            }
        }
    }
    if (confirmEnable) AlertDialog(onDismissRequest = { confirmEnable = false }, title = { Text("允许发送记忆给模型？") }, text = { Text("开启后，这里保存的记忆会随聊天请求发送给你配置的服务商。请避免保存身份证、支付信息等敏感内容。你可以随时关闭、修改和删除。") }, confirmButton = { TextButton(onClick = { settings.memoryEnabled = true; enabled = true; confirmEnable = false }) { Text("允许") } }, dismissButton = { TextButton(onClick = { confirmEnable = false }) { Text("取消") } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清空所有长期记忆？") }, text = { Text("这会删除本机的记忆，无法撤销。") }, confirmButton = { TextButton(onClick = { mutate { repository.clearMemories() }; confirmClear = false; text = ""; editingId = 0 }) { Text("清空") } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}

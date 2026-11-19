package com.cutedog.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cutedog.app.data.Reminder
import com.cutedog.app.data.ReminderRepository
import com.cutedog.app.reminder.ReminderScheduler
import com.cutedog.app.daily.DailyScheduler
import com.cutedog.app.daily.DailyRepository
import com.cutedog.app.daily.DailyQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CuteDogApp(initialReminderId: Long = 0L, openDaily: Boolean = false, launchSequence: Int = 0, openChat: Boolean = false, showMissed: Boolean = false) {
    val context = LocalContext.current
    val onboarding = remember { context.getSharedPreferences("onboarding", 0) }
    var introduced by rememberSaveable { mutableStateOf(onboarding.getBoolean("completed", false)) }
    if (!introduced && initialReminderId == 0L && !openDaily && !openChat && !showMissed) {
        OnboardingScreen { onboarding.edit().putBoolean("completed", true).apply(); introduced = true }
        return
    }
    val wide = LocalConfiguration.current.screenWidthDp >= 700
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val repository = remember { ReminderRepository(context.applicationContext) }
    val scheduler = remember { ReminderScheduler(context.applicationContext) }
    val daily = remember { DailyRepository(context.applicationContext) }
    var tab by rememberSaveable { mutableIntStateOf(if (initialReminderId > 0) 1 else 0) }
    var revision by remember { mutableIntStateOf(0) }
    var restoreRevision by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<Reminder>>(emptyList()) }
    var quote by remember { mutableStateOf<DailyQuote?>(null) }
    var editing by remember { mutableStateOf<Reminder?>(null) }
    var editingId by rememberSaveable { mutableLongStateOf(0L) }
    var editorVisible by rememberSaveable { mutableStateOf(false) }
    var editorDraftToken by rememberSaveable { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var occurrenceId by rememberSaveable { mutableLongStateOf(0) }
    var missedVisible by rememberSaveable { mutableStateOf(false) }
    val writes = remember(context) { ViewModelProvider(context as ComponentActivity)[ReminderWriteModel::class.java] }
    val savingReminder = writes.running
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        context.message(if (granted) "通知已开启，记得在系统设置中允许精确提醒。" else "通知未开启，提醒通知可能无法显示。可在小狗设置中重新开启。")
    }
    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(writes.result) {
        writes.result?.let { result ->
            if (result.savedId > 0) {
                if (editorDraftToken == result.draftToken) editorVisible = false
                revision++
                requestNotifications()
            }
            context.message(result.message)
            writes.consumeResult()
        }
    }
    LaunchedEffect(editorDraftToken, writes.committedDrafts) {
        if (writes.committedDrafts.containsKey(editorDraftToken)) { editorVisible = false; revision++ }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { restoreRevision++; revision++ } }
        lifecycle.addObserver(observer)
        val changes = object : android.content.BroadcastReceiver() {
            override fun onReceive(receiverContext: android.content.Context, intent: android.content.Intent) { revision++ }
        }
        androidx.core.content.ContextCompat.registerReceiver(context, changes,
            android.content.IntentFilter("com.cutedog.app.REMINDERS_CHANGED"), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(changes); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(restoreRevision) {
        try { withContext(Dispatchers.IO) { scheduler.restoreAll(); DailyScheduler(context).schedule() } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { context.message("提醒调度暂时未恢复，请检查系统权限后重新打开应用。") }
    }
    LaunchedEffect(revision) {
        try {
            items = withContext(Dispatchers.IO) { repository.list() }
            quote = withContext(Dispatchers.IO) { daily.today() }
            loadError = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { loadError = "读取本地数据失败，请重试。" }
        loading = false
    }
    LaunchedEffect(initialReminderId, openDaily, launchSequence, openChat, showMissed) {
        if (openDaily) { tab = 0; editorVisible = false }
        if (openChat) { tab = 2; editorVisible = false }
        if (showMissed) { tab = 1; missedVisible = true }
        if (initialReminderId > 0) {
            tab = 1
            editorDraftToken = java.util.UUID.randomUUID().toString()
            editingId = initialReminderId
            editing = withContext(Dispatchers.IO) { repository.get(initialReminderId) }
            editorVisible = editing != null
            if (editing == null) context.message("这件事项已经删除。")
        }
    }
    LaunchedEffect(editingId) {
        if (editingId > 0 && editing?.id != editingId) editing = withContext(Dispatchers.IO) { repository.get(editingId) }
    }
    fun edit(item: Reminder?) { editorDraftToken = java.util.UUID.randomUUID().toString(); editingId = item?.id ?: 0L; editing = item; editorVisible = true }
    fun completeGroup(item: Reminder) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.setCompleted(item.id, !item.completed)
                    repository.get(item.id)?.let { scheduler.schedule(it) }
                }
                revision++
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { context.message("更新失败，请重试。") }
        }
    }
    fun complete(item: Reminder) {
        if (!item.completed && item.repeat != "none") occurrenceId = item.id else completeGroup(item)
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!wide) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                listOf("首页", "事项", "陪伴", "小狗").forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, modifier = Modifier.testTag("nav_$index"),
                        icon = { NavGlyph(index, tab == index) }, label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer))
                }
            }
        }
    ) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
            if (wide) NavigationRail(containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxHeight()) {
                Spacer(Modifier.height(26.dp))
                listOf("首页", "事项", "陪伴", "小狗").forEachIndexed { index, label ->
                    NavigationRailItem(selected = tab == index, onClick = { tab = index }, icon = { NavGlyph(index, tab == index) }, label = { Text(label) }, modifier = Modifier.testTag("nav_$index"))
                    Spacer(Modifier.height(16.dp))
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 1000.dp).fillMaxSize()) {
            if (loadError != null) {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(loadError!!, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                        TextButton(onClick = { revision++ }) { Text("重试") }
                    }
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (tab) {
                0 -> HomeScreen(items, quote, { edit(null) }, { tab = 1 }, { tab = 2 }, { edit(it) }, { complete(it) }, {
                    quote?.let { current -> scope.launch { withContext(Dispatchers.IO) { daily.toggleFavorite(current.id) }; revision++ } }
                })
                1 -> RemindersScreen(items, { edit(null) }, { edit(it) }, { complete(it) }, { revision++ })
                2 -> ChatScreen()
                else -> SettingsScreen(onNotificationPermission = { requestNotifications() }, onRefresh = { revision++ })
            }
            }
        }
        }
    }
    if (missedVisible) ReminderDeliveryDialog({ missedVisible = false }, { missedVisible = false; edit(it) }, { revision++ })
    val occurrence = items.firstOrNull { it.id == occurrenceId }
    if (occurrence != null) AlertDialog(onDismissRequest = { occurrenceId = 0 }, title = { Text("完成这次，还是结束重复？") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${occurrence.title} · ${repeatLabel(occurrence.repeat)}。完成本次后会显示下一期事项；结束整组后可以在已完成中重新开启。")
            Button(onClick = { scope.launch { try { withContext(Dispatchers.IO) { scheduler.completeOccurrence(occurrence.id, occurrence.dueAt) }; revision++; occurrenceId = 0; context.message("本次已完成，下一次仍会提醒。") } catch (e: CancellationException) { throw e } catch (e: Exception) { context.message("操作失败，请重试。") } } }, modifier = Modifier.fillMaxWidth()) { Text("完成本次") }
            OutlinedButton(onClick = { occurrenceId = 0; completeGroup(occurrence) }, modifier = Modifier.fillMaxWidth()) { Text("结束整组重复") }
        }
    }, confirmButton = { TextButton(onClick = { occurrenceId = 0 }) { Text("取消") } })
    if (editorVisible && (editingId == 0L || editing?.id == editingId)) key(editingId) { ReminderEditor(editing, saving = savingReminder, onDismiss = { if (!savingReminder) editorVisible = false }, onSave = { reminder ->
        writes.save(context, reminder, editorDraftToken)
    }, onDelete = { item ->
        scope.launch {
            try { withContext(Dispatchers.IO) { repository.delete(item.id); scheduler.cancel(item.id) }; editorVisible = false; revision++ }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { context.message("删除失败，请重试。") }
        }
    })
    }
}

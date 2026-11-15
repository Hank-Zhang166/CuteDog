package com.cutedog.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CuteDogApp(initialReminderId: Long = 0L, openDaily: Boolean = false, launchSequence: Int = 0) {
    val context = LocalContext.current
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
    var savingReminder by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        context.message(if (granted) "通知已开启，记得在系统设置中允许精确提醒。" else "通知未开启，提醒通知可能无法显示。可在小狗设置中重新开启。")
    }
    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { restoreRevision++; revision++ } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(restoreRevision) {
        try { withContext(Dispatchers.IO) { scheduler.restoreAll(); DailyScheduler(context).schedule() } }
        catch (e: Exception) { context.message("提醒调度暂时未恢复，请检查系统权限后重新打开应用。") }
    }
    LaunchedEffect(revision) {
        try {
            items = withContext(Dispatchers.IO) { repository.list() }
            quote = withContext(Dispatchers.IO) { daily.today() }
            loadError = null
        } catch (e: Exception) { loadError = "读取本地数据失败，请重试。" }
        loading = false
    }
    LaunchedEffect(initialReminderId, openDaily, launchSequence) {
        if (openDaily) { tab = 0; editorVisible = false }
        if (initialReminderId > 0) {
            tab = 1
            editingId = initialReminderId
            editing = withContext(Dispatchers.IO) { repository.get(initialReminderId) }
            editorVisible = editing != null
            if (editing == null) context.message("这件事项已经删除。")
        }
    }
    LaunchedEffect(editingId) {
        if (editingId > 0 && editing?.id != editingId) editing = withContext(Dispatchers.IO) { repository.get(editingId) }
    }
    fun edit(item: Reminder?) { editingId = item?.id ?: 0L; editing = item; editorVisible = true }
    fun complete(item: Reminder) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.setCompleted(item.id, !item.completed)
                    repository.get(item.id)?.let { scheduler.schedule(it) }
                }
                revision++
            } catch (e: Exception) { context.message("更新失败，请重试。") }
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                listOf("首页", "事项", "陪伴", "小狗").forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { NavGlyph(index, tab == index) }, label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer))
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
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
                1 -> RemindersScreen(items, { edit(null) }, { edit(it) }, { complete(it) })
                2 -> ChatScreen()
                else -> SettingsScreen(onNotificationPermission = { requestNotifications() }, onRefresh = { revision++ })
            }
        }
    }
    if (editorVisible && (editingId == 0L || editing != null)) ReminderEditor(editing, saving = savingReminder, onDismiss = { if (!savingReminder) editorVisible = false }, onSave = save@{ reminder ->
        if (savingReminder) return@save
        savingReminder = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val id = repository.save(reminder)
                    repository.get(id)?.let { scheduler.schedule(it) }
                }
                editorVisible = false; revision++
                requestNotifications()
                context.message("事项已保存。可在小狗设置中检查提醒权限。")
            } catch (e: Exception) { context.message("保存失败：${e.message ?: "请稍后重试"}") }
            finally { savingReminder = false }
        }
    }, onDelete = { item ->
        scope.launch {
            try { withContext(Dispatchers.IO) { scheduler.cancel(item.id); repository.delete(item.id) }; editorVisible = false; revision++ }
            catch (e: Exception) { context.message("删除失败，请重试。") }
        }
    })
}

package com.cutedog.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cutedog.app.data.Reminder
import com.cutedog.app.reminder.ReminderDraftParser
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@Composable
internal fun RemindersScreen(reminders: List<Reminder>, onAdd: () -> Unit, onEdit: (Reminder) -> Unit, onComplete: (Reminder) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var prioritySort by rememberSaveable { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val today = LocalDate.now()
    val selected = reminders.filter { item ->
        val date = Instant.ofEpochMilli(item.dueAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val matches = when (filter) { 1 -> !item.completed && date == today; 2 -> !item.completed && item.dueAt > now; 3 -> !item.completed && item.dueAt < now; 4 -> item.completed; else -> !item.completed }
        matches && (item.title + item.details + item.category).contains(search, ignoreCase = true)
    }.let { if (prioritySort) it.sortedWith(compareByDescending<Reminder> { r -> r.priority }.thenBy { r -> r.dueAt }) else it.sortedBy { r -> r.dueAt } }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("把小事记好", style = MaterialTheme.typography.headlineLarge); Text("腾一点空间，给生活。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            FilledTonalButton(onClick = onAdd, contentPadding = PaddingValues(horizontal = 16.dp)) { Text("＋ 新建") }
        }
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索标题、内容或分类") }, shape = RoundedCornerShape(18.dp))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("待办", "今天", "即将", "逾期", "完成").forEachIndexed { index, label -> FilterChip(filter == index, { filter = index }, label = { Text(label) }) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${selected.size} 件事项", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { prioritySort = !prioritySort }) { Text(if (prioritySort) "优先级排序 ↓" else "时间排序 ↓") }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (selected.isEmpty()) item { EmptyNote(if (search.isNotEmpty()) "暂时没有找到" else "这里还很清爽", if (search.isNotEmpty()) "换一个词，或者试试其他分类。" else "点右上角新建，添加一件你在意的小事。") }
            items(selected, key = { it.id }) { item -> ReminderRow(item, { onEdit(item) }, { onComplete(item) }) }
        }
    }
}

@Composable
internal fun ReminderRow(item: Reminder, onClick: () -> Unit, onComplete: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(start = 8.dp, end = 16.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(item.completed, { onComplete() })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, textDecoration = if (item.completed) TextDecoration.LineThrough else null)
                val overdue = !item.completed && item.dueAt < System.currentTimeMillis()
                Text((if (overdue) "已逾期 · " else "") + formatTime(item.dueAt), style = MaterialTheme.typography.labelSmall, color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.details.isNotBlank()) Text(item.details, maxLines = 2, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(item.category + if (item.repeat != "none") " · ${repeatLabel(item.repeat)}" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
            if (item.priority >= 3) Text("重要", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

internal fun repeatLabel(value: String) = when (value) { "daily" -> "每天"; "weekly" -> "每周"; "monthly" -> "每月"; else -> "不重复" }

@Composable
internal fun ReminderEditor(original: Reminder?, saving: Boolean = false, onDismiss: () -> Unit, onSave: (Reminder) -> Unit, onDelete: (Reminder) -> Unit) {
    val context = LocalContext.current
    var title by rememberSaveable { mutableStateOf(original?.title ?: "") }
    var details by rememberSaveable { mutableStateOf(original?.details ?: "") }
    var dueAt by rememberSaveable { mutableLongStateOf(original?.dueAt ?: System.currentTimeMillis() + 3600000) }
    var lead by rememberSaveable { mutableStateOf((original?.leadMinutes ?: 0).toString()) }
    var repeat by rememberSaveable { mutableStateOf(original?.repeat ?: "none") }
    var priority by rememberSaveable { mutableIntStateOf(original?.priority ?: 2) }
    var category by rememberSaveable { mutableStateOf(original?.category ?: "生活") }
    var error by remember { mutableStateOf<String?>(null) }
    var deleteConfirm by remember { mutableStateOf(false) }
    fun pickTime() {
        val date = Instant.ofEpochMilli(dueAt).atZone(ZoneId.systemDefault())
        DatePickerDialog(context, { _, year, month, day ->
            TimePickerDialog(context, { _, hour, minute ->
                val wallTime = LocalDateTime.of(year, month + 1, day, hour, minute)
                if (ZoneId.systemDefault().rules.getValidOffsets(wallTime).isEmpty()) context.message("所选时间处于夏令时跳转空档，请选择其他时间。")
                else dueAt = wallTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }, date.hour, date.minute, true).show()
        }, date.year, date.monthValue - 1, date.dayOfMonth).show()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.94f).padding(horizontal = 12.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(22.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (original == null) "记一件小事" else "编辑事项", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("重要的事情，我陪你一起记得。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(title, { title = it.take(200) }, modifier = Modifier.fillMaxWidth(), label = { Text("标题（必填）") }, singleLine = true, shape = RoundedCornerShape(16.dp))
                    TextButton(onClick = {
                        val draft = ReminderDraftParser.parse(title)
                        if (draft == null) error = "未识别到明确的未来时间。可试试「明天下午三点记得给老师发邮件」。"
                        else { title = draft.title; dueAt = draft.dueAt; error = null; context.message(draft.explanation) }
                    }, enabled = title.isNotBlank()) { Text("从标题识别时间 · 保存前请确认") }
                    OutlinedTextField(details, { details = it.take(10000) }, modifier = Modifier.fillMaxWidth(), label = { Text("详细内容") }, minLines = 3, maxLines = 6, shape = RoundedCornerShape(16.dp))
                    OutlinedButton(onClick = { pickTime() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("时间  ${formatTime(dueAt)}", modifier = Modifier.padding(vertical = 8.dp)) }
                    Text("重复方式", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("none", "daily", "weekly", "monthly").forEach { value -> FilterChip(repeat == value, { repeat = value }, label = { Text(repeatLabel(value)) }) }
                    }
                    OutlinedTextField(lead, { lead = it.filter(Char::isDigit).take(5) }, modifier = Modifier.fillMaxWidth(), label = { Text("提前多少分钟提醒（0 为准时）") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), shape = RoundedCornerShape(16.dp))
                    OutlinedTextField(category, { category = it.take(24) }, modifier = Modifier.fillMaxWidth(), label = { Text("分类") }, singleLine = true, shape = RoundedCornerShape(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1 to "低", 2 to "普通", 3 to "重要").forEach { (value, label) -> FilterChip(priority == value, { priority = value }, label = { Text(label) }) }
                    }
                    Text("精确提醒需要系统授权；未授权时会采用系统允许的非精确提醒。重启后会恢复未完成事项。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    if (original != null) TextButton(onClick = { deleteConfirm = true }) { Text("删除这件事项", color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.height(8.dp))
                }
                Button(onClick = {
                    val minutes = lead.toIntOrNull()
                    error = when { title.isBlank() -> "请给事项起个名字。"; minutes == null || minutes !in 0..10080 -> "提前量应在 0 到 10080 分钟之间。"; category.isBlank() -> "请填写分类。"; dueAt <= System.currentTimeMillis() && (original == null || dueAt != original.dueAt) -> "请选择将来的提醒时间。"; else -> null }
                    if (error == null) {
                        val item = original?.copy(title = title.trim(), details = details.trim(), dueAt = dueAt, leadMinutes = minutes!!, repeat = repeat, priority = priority, category = category.trim(), snoozedUntil = 0, anchorDay = if (dueAt != original.dueAt) 0 else original.anchorDay, timeZoneId = if (dueAt != original.dueAt) ZoneId.systemDefault().id else original.timeZoneId)
                            ?: Reminder(title = title.trim(), details = details.trim(), dueAt = dueAt, leadMinutes = minutes!!, repeat = repeat, priority = priority, category = category.trim())
                        onSave(item)
                    }
                }, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) { Text(if (saving) "正在保存…" else "保存事项") }
            }
        }
    }
    if (deleteConfirm && original != null) AlertDialog(onDismissRequest = { deleteConfirm = false }, title = { Text("删除这件事项？") }, text = { Text("对应提醒也会取消，此操作无法撤销。") }, confirmButton = { TextButton(onClick = { deleteConfirm = false; onDelete(original) }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("保留") } })
}

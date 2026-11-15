package com.cutedog.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cutedog.app.data.Reminder
import com.cutedog.app.data.ReminderRepository
import com.cutedog.app.reminder.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ReminderDeliveryDialog(onDismiss: () -> Unit, onEdit: (Reminder) -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { ReminderRepository(context.applicationContext) }
    val scheduler = remember { ReminderScheduler(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var pendingOnly by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val records by produceState(initialValue = emptyList<com.cutedog.app.data.DeliveryRecord>(), revision, pendingOnly) {
        try { value = withContext(Dispatchers.IO) { if (pendingOnly) repository.pendingDeliveries() else repository.deliveryHistory() }; error = null }
        catch (e: Exception) { error = "暂时无法读取提醒记录，请重试。" }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight(.9f).padding(14.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("提醒有没有抵达", style = MaterialTheme.typography.titleLarge)
                Text("权限或系统限制导致的未送达提醒会留在这里。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(pendingOnly, { pendingOnly = true }, label = { Text("待处理") }); FilterChip(!pendingOnly, { pendingOnly = false }, label = { Text("最近记录") }) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (records.isEmpty()) item { EmptyNote("没有待查看的记录", "新的提醒状态会在这里显示。") }
                    items(records, key = { "${it.reminderId}:${it.token}" }) { item ->
                        SoftCard(Modifier.fillMaxWidth()) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium)
                            Text(formatTime(item.dueAt), style = MaterialTheme.typography.labelSmall)
                            Text(item.reason.ifBlank { when (item.status) { "legacy" -> "旧版本记录（送达状态未知）"; "sent" -> "已发送通知"; "acknowledged" -> "已查看"; "summarized" -> "已汇总提醒"; else -> "等待恢复提醒" } }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row { TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { repository.get(item.reminderId) }?.let(onEdit) } }) { Text("查看事项") }; if (pendingOnly) TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { repository.acknowledgeDelivery(item.reminderId, item.token) }; revision++; onChanged() } }) { Text("知道了") } }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { scope.launch { try { withContext(Dispatchers.IO) { scheduler.retryPending() }; revision++; onChanged() } catch (e: Exception) { error = "重试失败，请检查通知权限。" } } }) { Text("重试待处理提醒") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

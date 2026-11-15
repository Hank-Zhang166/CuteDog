package com.cutedog.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModelProvider
import com.cutedog.app.reminder.ReminderBackup
import com.cutedog.app.reminder.BackupSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
internal fun ReminderBackupDialog(onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val backup = remember { ReminderBackup(context.applicationContext) }
    val model = remember(context) { ViewModelProvider(context as ComponentActivity).get("document_reminder_import", DocumentTransferModel::class.java) }
    val scope = rememberCoroutineScope()
    var count by rememberSaveable { mutableIntStateOf(-1) }
    var duplicates by rememberSaveable { mutableIntStateOf(0) }
    var replace by rememberSaveable { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var rollbackId by rememberSaveable { mutableStateOf<String?>(null) }
    var snapshots by remember { mutableStateOf<List<BackupSnapshot>>(emptyList()) }
    var snapshotsRevision by remember { mutableIntStateOf(0) }
    var restoreSnapshot by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteSnapshot by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(snapshotsRevision) { try { snapshots = withContext(Dispatchers.IO) { backup.availableBackups() } } catch (e: Exception) { error = "无法读取本机恢复副本。" } }
    val export = rememberJsonExporter("reminders", "CuteDog-事项-${LocalDate.now()}.json") { backup.exportJson() }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; error = null; status = null
            try {
                val json = withContext(Dispatchers.IO) { readBoundedJson(context, uri) }
                val preview = withContext(Dispatchers.IO) { backup.previewJson(json) }
                model.importText = json; count = preview.count; duplicates = preview.duplicates
            } catch (e: Exception) { count = -1; model.importText = null; error = e.message ?: "无法识别这个备份文件。" }
            finally { busy = false }
        }
    }
    fun importNow() {
        val json = model.importText ?: run { error = "文件选择已中断，请重新选择备份。"; return }
        busy = true; error = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { backup.importJson(json, if (replace) "replace" else "merge") }
                rollbackId = result.backupId; snapshotsRevision++
                status = "已导入 ${result.count} 件事项，跳过 ${result.duplicates} 件重复事项。"
                model.importText = null; count = -1; onChanged()
            } catch (e: Exception) { error = e.message ?: "导入失败，原有事项未被替换。" }
            finally { busy = false }
        }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(.9f).padding(16.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("把事项安心保存", style = MaterialTheme.typography.headlineMedium)
                Text("备份文件只包含事项，不包含聊天、记忆或密钥。请保存在你信任的位置。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = export, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("reminders_export")) { Text("导出事项备份") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("reminders_import")) { Text("选择备份文件") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (count >= 0) SoftCard(Modifier.fillMaxWidth()) {
                    Text("准备恢复 $count 件事项", style = MaterialTheme.typography.titleMedium)
                    Text("其中 $duplicates 件与本机已有事项重复。确认后才会写入。", style = MaterialTheme.typography.bodyMedium)
                    Row { RadioButton(!replace, { replace = false }); TextButton(onClick = { replace = false }) { Text("合并，保留原有事项（推荐）") } }
                    Row { RadioButton(replace, { replace = true }); TextButton(onClick = { replace = true }) { Text("替换本机全部事项") } }
                    Button(onClick = { if (replace) confirmReplace = true else importNow() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("确认恢复") }
                }
                status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                rollbackId?.let { id -> OutlinedButton(onClick = {
                    busy = true
                    scope.launch { try { withContext(Dispatchers.IO) { backup.restoreBackup(id) }; rollbackId = null; snapshotsRevision++; status = "已恢复替换前的事项。"; onChanged() } catch (e: Exception) { error = e.message ?: "恢复失败。" } finally { busy = false } }
                }, enabled = !busy) { Text("撤销本次替换") } }
                if (snapshots.isNotEmpty()) {
                    HorizontalDivider()
                    Text("本机恢复副本", style = MaterialTheme.typography.titleMedium)
                    Text("替换前的副本会保留在本机，包括当时的事项。删除事项不会自动删除副本；不再需要时，请在这里删除。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    snapshots.forEach { snapshot ->
                        SoftCard(Modifier.fillMaxWidth()) {
                            Text(formatTime(snapshot.createdAt), style = MaterialTheme.typography.labelLarge)
                            Row { TextButton(onClick = { restoreSnapshot = snapshot.id }, enabled = !busy) { Text("恢复这个副本") }; TextButton(onClick = { deleteSnapshot = snapshot.id }, enabled = !busy) { Text("删除副本") } }
                        }
                    }
                }
                Text("恢复后会重新安排未完成事项的提醒。已过去的提醒将按应用的补提醒规则处理。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("完成") }
            }
        }
    }
    restoreSnapshot?.let { id -> AlertDialog(onDismissRequest = { restoreSnapshot = null }, title = { Text("恢复到这个副本？") }, text = { Text("当前事项与提醒会由该副本替换。请先导出你需要保留的最新事项。") }, confirmButton = { TextButton(onClick = { restoreSnapshot = null; busy = true; scope.launch { try { withContext(Dispatchers.IO) { backup.restoreBackup(id) }; snapshotsRevision++; status = "副本已恢复。"; onChanged() } catch (e: Exception) { error = e.message ?: "恢复失败。" } finally { busy = false } } }) { Text("确认恢复") } }, dismissButton = { TextButton(onClick = { restoreSnapshot = null }) { Text("取消") } }) }
    deleteSnapshot?.let { id -> AlertDialog(onDismissRequest = { deleteSnapshot = null }, title = { Text("删除这个恢复副本？") }, text = { Text("不会删除当前事项，但这个旧副本将无法再恢复。") }, confirmButton = { TextButton(onClick = { deleteSnapshot = null; scope.launch { try { withContext(Dispatchers.IO) { backup.deleteBackup(id) }; snapshotsRevision++; if (rollbackId == id) rollbackId = null } catch (e: Exception) { error = "删除失败，请重试。" } } }) { Text("删除副本") } }, dismissButton = { TextButton(onClick = { deleteSnapshot = null }) { Text("取消") } }) }
    if (confirmReplace) AlertDialog(onDismissRequest = { confirmReplace = false }, title = { Text("替换本机全部事项？") }, text = { Text("当前事项及对应提醒将由备份内容替换。应用会先在本机保存恢复副本，你可以在本次操作后撤销。") }, confirmButton = { TextButton(onClick = { confirmReplace = false; importNow() }) { Text("确认替换") } }, dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("取消") } })
}

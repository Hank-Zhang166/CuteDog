package com.cutedog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.cutedog.app.daily.DailyQuote
import com.cutedog.app.data.Reminder
import com.cutedog.app.pet.PetSettings
import com.cutedog.app.pet.PetView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun HomeScreen(
    reminders: List<Reminder>, quote: DailyQuote?, onAdd: () -> Unit, onTasks: () -> Unit,
    onChat: () -> Unit, onEdit: (Reminder) -> Unit, onComplete: (Reminder) -> Unit, onFavorite: () -> Unit
) {
    val context = LocalContext.current
    val name = remember { PetSettings(context).name }
    val today = LocalDate.now()
    val todayTasks = reminders.filter { !it.completed && Instant.ofEpochMilli(it.dueAt).atZone(ZoneId.systemDefault()).toLocalDate() == today }.sortedBy { it.dueAt }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(today.format(DateTimeFormatter.ofPattern("M月d日 · EEEE", Locale.CHINESE)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(when (java.time.LocalTime.now().hour) { in 5..10 -> "早安，慢慢来"; in 11..17 -> "今天，也有我陪你"; else -> "辛苦了，歇一会儿" }, style = MaterialTheme.typography.headlineMedium)
            }
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Text("C\nD", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, lineHeight = 14.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surfaceVariant)))) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().padding(top = 20.dp, start = 20.dp, end = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("$name 的小小世界", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("轻触 · 打个招呼", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                AndroidView(factory = { PetView(it) }, modifier = Modifier.fillMaxWidth().height(242.dp), update = { it.contentDescription = "毛茸茸的萨摩耶$name，轻触互动" })
                Text("把平凡的日子，过得毛茸茸。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(18.dp))
            }
        }
        if (quote != null) {
            SoftCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("一日一暖", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    TextButton(onClick = onFavorite, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (quote.favorite) "已珍藏 ♥" else "珍藏 ♡") }
                }
                Text(quote.text, style = MaterialTheme.typography.titleMedium, lineHeight = 29.sp)
                Text("送给今天的你", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onAdd, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(18.dp)) { Text("＋ 记一件事") }
            OutlinedButton(onClick = onChat, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(18.dp)) { Text("聊一会儿") }
        }
        Column {
            SectionTitle("今日小事 · ${todayTasks.size}", "全部事项", onTasks)
            if (todayTasks.isEmpty()) EmptyNote("今天的留白，也很好。", "有想记住的事，就交给我。提醒会保存在这台设备上。")
            else todayTasks.take(4).forEach { item -> ReminderRow(item, { onEdit(item) }, { onComplete(item) }) }
        }
        Text("C U T E D O G  ·  陪伴每一个普通日子", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(8.dp))
    }
}

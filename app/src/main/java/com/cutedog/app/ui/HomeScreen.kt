package com.cutedog.app.ui

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cutedog.app.daily.DailyQuote
import com.cutedog.app.data.Reminder
import com.cutedog.app.pet.behavior.GrowthStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeScreen(
    reminders: List<Reminder>, quote: DailyQuote?, onAdd: () -> Unit, onTasks: () -> Unit,
    onChat: () -> Unit, onPetSettings: () -> Unit, onEdit: (Reminder) -> Unit, onComplete: (Reminder) -> Unit, onFavorite: () -> Unit
) {
    val context = LocalContext.current
    val growth = remember { GrowthStore(context.applicationContext) }
    var growthSnapshot by remember { mutableStateOf(growth.snapshot()) }
    var clearGrowth by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(growth) {
        val preferences = context.applicationContext.getSharedPreferences("pet_growth", 0)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> growthSnapshot = growth.snapshot() }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val today = LocalDate.now()
    val todayTasks = reminders.filter { !it.completed && Instant.ofEpochMilli(it.dueAt).atZone(ZoneId.systemDefault()).toLocalDate() == today }.sortedBy { it.dueAt }
    Column(Modifier.fillMaxSize().testTag("home_screen").verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
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
        TextButton(onClick = onPetSettings, modifier = Modifier.testTag("home_pet_settings")) {
            Text("桌宠设置与悬浮窗权限")
        }
        if (quote != null) {
            SoftCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("一日一暖", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    TextButton(onClick = onFavorite, modifier = Modifier.testTag("daily_favorite"), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (quote.favorite) "已珍藏 ♥" else "珍藏 ♡") }
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
        SoftCard(Modifier.fillMaxWidth().testTag("growth_card")) {
            Text("我们的日常", style = MaterialTheme.typography.titleMedium)
            Text("相伴第 ${growthSnapshot.daysTogether.toLong() + 1} 天", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text("${growthSnapshot.interactions} 次温柔互动", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("growth_interactions").semantics { contentDescription = "互动纪念，${growthSnapshot.interactions} 次互动"; liveRegion = LiveRegionMode.Polite })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                growthSnapshot.milestones.forEach { milestone ->
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) { Text(milestone, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
                }
            }
            TextButton(onClick = { clearGrowth = true }, modifier = Modifier.testTag("growth_reset")) { Text("重新记录相伴时光") }
        }
        Text("C U T E D O G  ·  陪伴每一个普通日子", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(8.dp))
    }
    if (clearGrowth) AlertDialog(onDismissRequest = { clearGrowth = false }, title = { Text("从今天重新记录相伴时光？") }, text = { Text("这会重置相伴天数与互动纪念。备忘事项、聊天、长期记忆和设置都会保留，所有玩具仍可使用。") }, confirmButton = { TextButton(onClick = { growthSnapshot = growth.clear(); clearGrowth = false }, modifier = Modifier.testTag("growth_reset_confirm")) { Text("重新记录") } }, dismissButton = { TextButton(onClick = { clearGrowth = false }) { Text("保留纪念") } })
}

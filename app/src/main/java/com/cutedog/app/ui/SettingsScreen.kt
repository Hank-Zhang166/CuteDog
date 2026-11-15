package com.cutedog.app.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cutedog.app.daily.*
import com.cutedog.app.pet.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsScreen(onNotificationPermission: () -> Unit, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PetSettings(context.applicationContext) }
    val daily = remember { DailySettings(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(prefs.name) }
    var size by remember { mutableFloatStateOf(prefs.size.toFloat()) }
    var opacity by remember { mutableFloatStateOf(prefs.opacity) }
    var activity by remember { mutableStateOf(prefs.activity) }
    var locked by remember { mutableStateOf(prefs.locked) }
    var boot by remember { mutableStateOf(prefs.boot) }
    var reduced by remember { mutableStateOf(prefs.reducedMotion) }
    var haptics by remember { mutableStateOf(prefs.haptics) }
    var sound by remember { mutableStateOf(prefs.soundEnabled) }
    var volume by remember { mutableFloatStateOf(prefs.soundVolume) }
    var dailyEnabled by remember { mutableStateOf(daily.enabled) }
    var hour by remember { mutableStateOf(daily.hour.toString()) }
    var quietStart by remember { mutableStateOf(daily.quietStart.toString()) }
    var quietEnd by remember { mutableStateOf(daily.quietEnd.toString()) }
    var historyVisible by remember { mutableStateOf(false) }
    var aiSettings by remember { mutableStateOf(false) }
    var helpVisible by remember { mutableStateOf(false) }
    var policyVisible by remember { mutableStateOf(false) }
    var regionsVisible by rememberSaveable { mutableStateOf(false) }
    var gesturesVisible by rememberSaveable { mutableStateOf(false) }
    var introductionVisible by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var permissionRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) permissionRevision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (introductionVisible) { OnboardingScreen { introductionVisible = false }; return }
    val overlay = remember(permissionRevision) { Settings.canDrawOverlays(context) }
    val notifications = remember(permissionRevision) { context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() }
    val precise = remember(permissionRevision) { Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }
    fun startOverlay() {
        if (!Settings.canDrawOverlays(context)) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            context.message("开启悬浮窗权限后，请回到这里点击「召唤小狗」。")
        } else {
            try { OverlayService.start(context); onNotificationPermission(); context.message("小狗正在来到屏幕上。长按拖动，通知中可以隐藏。") }
            catch (e: Exception) { context.message("系统暂时未允许启动桌宠，请保持应用在前台后重试。") }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).testTag("pet_settings_screen"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Spacer(Modifier.height(4.dp))
        Text("你的小狗，你的节奏", style = MaterialTheme.typography.headlineMedium)
        Text("安静陪伴，也认真照顾每一件小事。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SoftCard(Modifier.fillMaxWidth()) {
            Text("给它一个名字", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, { name = it.take(16) }, label = { Text("小狗的名字") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp))
            TextButton(onClick = { if (name.isNotBlank()) { prefs.name = name.trim(); context.message("以后就叫我${name.trim()}啦。") } else context.message("名字不能为空。") }) { Text("保存名字") }
        }
        SoftCard(Modifier.fillMaxWidth()) {
            Text("陪你走出应用", style = MaterialTheme.typography.titleLarge)
            Text("桌宠会显示在桌面及其他应用上层。只占用角色附近的小窗口，不读取屏幕内容或桌面图标。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { startOverlay() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text(if (overlay) "召唤小狗" else "授权悬浮窗并召唤") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { OverlayService.hide(context) }) { Text("临时隐藏") }
                TextButton(onClick = { if (Settings.canDrawOverlays(context)) OverlayService.show(context) else startOverlay() }) { Text("恢复显示") }
                TextButton(onClick = { OverlayService.stop(context); context.message("桌宠已停止。备忘提醒仍会保留。") }) { Text("停止桌宠") }
            }
            HorizontalDivider()
            Text("显示大小", style = MaterialTheme.typography.labelLarge)
            Slider(size, { size = it }, valueRange = 96f..220f, onValueChangeFinished = { prefs.size = size.toInt() })
            Text("不透明度 · ${(opacity * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
            Slider(opacity, { opacity = it }, valueRange = .3f..1f, onValueChangeFinished = { prefs.opacity = opacity })
            Text("活动节奏", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("quiet" to "安静", "balanced" to "平衡", "lively" to "活泼").forEach { (value, label) -> FilterChip(activity == value, { activity = value; prefs.activity = value }, label = { Text(label) }) }
            }
            SettingSwitch("锁定位置", "锁定后不再通过拖动改变位置。", locked) { locked = it; prefs.locked = it }
            SettingSwitch("开机后恢复陪伴", "受系统后台启动限制；必要时从通知或应用内恢复。", boot) { boot = it; prefs.boot = it }
            SettingSwitch("减少动态效果", "降低自主动作强度，减少视觉干扰。", reduced) { reduced = it; prefs.reducedMotion = it }
            SettingSwitch("轻触振动", "仅用于你主动与小狗互动时。", haptics) { haptics = it; prefs.haptics = it }
            SettingSwitch("互动音效", "默认关闭；静音、勿扰与休息时不主动发声。", sound) { sound = it; prefs.soundEnabled = it }
            if (sound) {
                Text("音效音量", style = MaterialTheme.typography.labelLarge)
                Slider(volume, { volume = it }, valueRange = 0f.. .7f, onValueChangeFinished = { prefs.soundVolume = volume })
            }
            OutlinedButton(onClick = { regionsVisible = true }, modifier = Modifier.fillMaxWidth().testTag("pet_regions")) { Text("活动范围与避让区域") }
            OutlinedButton(onClick = { gesturesVisible = true }, modifier = Modifier.fillMaxWidth().testTag("pet_gestures")) { Text("手势与默认位置") }
            TextButton(onClick = { helpVisible = true }) { Text("查看互动方式") }
        }
        SoftCard(Modifier.fillMaxWidth()) {
            Text("让提醒顺利抵达", style = MaterialTheme.typography.titleLarge)
            PermissionRow("悬浮窗", overlay, "桌宠显示需要此权限。") { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
            PermissionRow("通知", notifications, "到期提醒通过系统通知送达。") {
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) onNotificationPermission()
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
            PermissionRow("精确闹钟", precise, "未授权时采用可能延迟的非精确提醒。") {
                if (Build.VERSION.SDK_INT >= 31) context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                else context.message("此系统版本不需要单独授权精确闹钟。")
            }
            Text("部分手机会限制后台与自启动。可在系统的应用电池设置中按需放宽限制。系统强行停止应用后，需要重新打开应用才能恢复提醒。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("打开系统应用设置") }
        }
        SoftCard(Modifier.fillMaxWidth()) {
            Text("每天一句暖心话", style = MaterialTheme.typography.titleLarge)
            SettingSwitch("每日问候", "每日最多主动提醒一次；勿扰时段内不打扰。", dailyEnabled) { dailyEnabled = it; daily.enabled = it; scope.launch { withContext(Dispatchers.IO) { DailyScheduler(context).schedule() } } }
            OutlinedTextField(hour, { hour = it.filter(Char::isDigit).take(2) }, label = { Text("问候时间（0–23 点）") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), shape = RoundedCornerShape(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(quietStart, { quietStart = it.filter(Char::isDigit).take(2) }, label = { Text("勿扰开始（点）") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), shape = RoundedCornerShape(16.dp))
                OutlinedTextField(quietEnd, { quietEnd = it.filter(Char::isDigit).take(2) }, label = { Text("勿扰结束（点）") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), shape = RoundedCornerShape(16.dp))
            }
            Text("开始与结束相同表示不设勿扰时段。每日问候使用非精确调度，可能随系统节电而延后。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = {
                val h = hour.toIntOrNull(); val start = quietStart.toIntOrNull(); val end = quietEnd.toIntOrNull()
                if (h == null || start == null || end == null || h !in 0..23 || start !in 0..23 || end !in 0..23) context.message("时间请填写 0 到 23 的整数。")
                else { daily.hour = h; daily.quietStart = start; daily.quietEnd = end; scope.launch { withContext(Dispatchers.IO) { DailyScheduler(context).schedule() } }; context.message("问候时间已保存。") }
            }) { Text("保存问候时间") }
            OutlinedButton(onClick = { historyVisible = true }, modifier = Modifier.fillMaxWidth()) { Text("翻看历史与珍藏") }
        }
        SoftCard(Modifier.fillMaxWidth()) {
            Text("陪伴，也尊重边界", style = MaterialTheme.typography.titleLarge)
            Text("事项、聊天与记忆默认保存在本机；联网聊天需使用你自己的模型服务。没有扣分、饥饿惩罚或强制互动。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { aiSettings = true }, modifier = Modifier.fillMaxWidth()) { Text("模型服务设置") }
            TextButton(onClick = { policyVisible = true }) { Text("隐私与使用说明") }
            TextButton(onClick = { introductionVisible = true }) { Text("重新看看新手引导") }
        }
        Text("CuteDog · 用温柔陪伴日常\nAndroid 原生 · 本地优先", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(12.dp))
    }
    if (regionsVisible) PetRegionEditor { regionsVisible = false }
    if (gesturesVisible) GestureSettingsDialog { gesturesVisible = false }
    if (historyVisible) QuoteHistoryDialog { historyVisible = false; onRefresh() }
    if (aiSettings) AiSettingsDialog { aiSettings = false }
    if (helpVisible) AlertDialog(onDismissRequest = { helpVisible = false }, title = { Text("和小狗相处的小方法") }, text = { Text("轻触：引起注意或打开应用。\n\n短距离轻抚：给小狗一点温柔的回应。\n\n长按后拖动：移动桌宠，松手后靠近边缘。\n\n临时隐藏：在本页或常驻通知中操作；需要时再恢复。\n\n锁定位置与减少动态效果可在此页调整。桌宠不会识别其他应用中的图标或文字。") }, confirmButton = { TextButton(onClick = { helpVisible = false }) { Text("知道啦") } })
    if (policyVisible) AlertDialog(onDismissRequest = { policyVisible = false }, title = { Text("你的数据，由你决定") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text("CuteDog 不要求登录，不读取通讯录、位置、其他应用内容或屏幕。\n\n备忘录、设置、聊天和长期记忆保存在应用私有存储。API Key 使用 Android Keystore 加密。卸载或清除应用数据会删除本地内容，请先导出需要的记忆。\n\n只有主动发起联网聊天时，消息和近期上下文才发送给你设置的模型服务商。长期记忆默认关闭，开启后会随请求发送。服务商可能收取费用并按其隐私政策处理数据。\n\n你可以删除聊天、编辑或清空记忆、清除密钥。删除本机数据不会删除服务商已接收的数据。\n\n系统强制停止、通知拒绝、节电限制或设备关机可能影响提醒。重要事项请保留独立提醒渠道。") } }, confirmButton = { TextButton(onClick = { policyVisible = false }) { Text("知道了") } })
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, onChange)
    }
}

@Composable
private fun PermissionRow(title: String, allowed: Boolean, detail: String, action: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        TextButton(onClick = action) { Text(if (allowed) "已开启" else "去设置", color = if (allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun QuoteHistoryDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { DailyRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var favoritesOnly by remember { mutableStateOf(false) }
    var quotes by remember { mutableStateOf<List<DailyQuote>>(emptyList()) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(favoritesOnly, revision) { quotes = withContext(Dispatchers.IO) { if (favoritesOnly) repository.favorites() else repository.history() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 14.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(22.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("把温柔收藏起来", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); TextButton(onClick = onDismiss) { Text("关闭") } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(!favoritesOnly, { favoritesOnly = false }, label = { Text("历史") }); FilterChip(favoritesOnly, { favoritesOnly = true }, label = { Text("珍藏") }) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (quotes.isEmpty()) item { EmptyNote("温柔会慢慢积攒", "每天打开应用看一句，也可以把喜欢的话珍藏起来。") }
                    items(quotes, key = { "${it.id}-${it.date}" }) { quote -> SoftCard(Modifier.fillMaxWidth()) {
                        Text(quote.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(quote.text, style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { repository.toggleFavorite(quote.id) }; revision++ } }) { Text(if (quote.favorite) "已珍藏 ♥" else "珍藏 ♡") }
                    } }
                }
            }
        }
    }
}

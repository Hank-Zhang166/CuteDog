package com.cutedog.app.ui

import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cutedog.app.pet.PetSettings
import com.cutedog.app.pet.PetView

/** No permission request or remote connection happens during introduction. */
@Composable
internal fun OnboardingScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PetSettings(context.applicationContext) }
    var step by rememberSaveable { mutableIntStateOf(0) }
    var quiet by rememberSaveable { mutableStateOf(prefs.activity == "quiet") }
    var reduced by rememberSaveable { mutableStateOf(prefs.reducedMotion) }
    val headings = listOf("你好，我是${prefs.name}", "记住小事，腾出心情", "把陪伴带到屏幕上", "你来决定，记住什么", "我们慢慢来")
    val descriptions = listOf(
        "一只住在手机里的萨摩耶。你可以轻轻摸摸我，也可以只是让我安静地陪一会儿。",
        "记下一个约定，选好时间，我会用通知提醒你。没有网络时，事项也会留在手机里。",
        "想让我走出应用时，在「小狗」里召唤我。轻抚是摸摸，长按后拖动是搬家；你也可以圈出活动范围。",
        "聊天需要你选择并连接模型服务。发送前会说明哪些内容交给服务商；长期记忆默认关闭，只记录你亲自同意的内容。",
        "不用每天打卡，也没有错过互动的惩罚。选择舒服的节奏，然后开始属于我们的普通日子。"
    )
    Surface(Modifier.fillMaxSize().testTag("onboarding"), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("C U T E D O G", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = onFinish, modifier = Modifier.testTag("onboarding_skip")) { Text("稍后了解") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    Spacer(Modifier.height(8.dp))
                    Surface(shape = RoundedCornerShape(40.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        AndroidView(factory = { PetView(it) }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 230.dp).height(210.dp), update = { it.contentDescription = "萨摩耶${prefs.name}欢迎你" })
                    }
                    Text(headings[step], style = MaterialTheme.typography.headlineLarge, modifier = Modifier.fillMaxWidth().semantics { heading() })
                    Text(descriptions[step], style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                    when (step) {
                        1 -> SoftCard(Modifier.fillMaxWidth()) { Text("用自己的话创建事项", style = MaterialTheme.typography.titleMedium); Text("试着写「明天下午三点记得给老师发邮件」，识别后由你确认时间。\n\n需要提醒时再开启通知与精确提醒权限。", style = MaterialTheme.typography.bodyMedium) }
                        2 -> SoftCard(Modifier.fillMaxWidth()) { Text("随时可以停下来", style = MaterialTheme.typography.titleMedium); Text("通知和应用里都能隐藏或停止桌宠。它不会读取其他应用中的文字或桌面图标。", style = MaterialTheme.typography.bodyMedium) }
                        3 -> SoftCard(Modifier.fillMaxWidth()) { Text("留下什么，由你决定", style = MaterialTheme.typography.titleMedium); Text("查看、修改、删除或导出自己的聊天和记忆。备忘录、日句与桌宠都可以离线使用。", style = MaterialTheme.typography.bodyMedium) }
                        4 -> {
                            OnboardingToggle("更安静地陪伴", "减少主动走动，适合专注和休息。", quiet) { quiet = it; prefs.activity = if (it) "quiet" else "balanced" }
                            OnboardingToggle("减少动态效果", "降低动作强度，给眼睛更多休息。", reduced) { reduced = it; prefs.reducedMotion = it }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(14.dp))
                Text("${step + 1} / 5", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (step > 0) OutlinedButton(onClick = { step-- }, modifier = Modifier.heightIn(min = 52.dp)) { Text("上一步") }
                    Button(onClick = { if (step == 4) onFinish() else step++ }, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("onboarding_next"), shape = RoundedCornerShape(18.dp)) { Text(if (step == 4) "开始陪伴" else "继续") }
                }
            }
        }
    }
}

@Composable
private fun OnboardingToggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, onChange)
    }
}

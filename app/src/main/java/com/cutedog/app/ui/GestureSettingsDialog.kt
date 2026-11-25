package com.cutedog.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cutedog.app.pet.OverlayService
import com.cutedog.app.pet.PetSettings

@Composable
internal fun GestureSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PetSettings(context) }
    var tap by rememberSaveable { mutableStateOf(prefs.tapAction) }
    var petting by rememberSaveable { mutableStateOf(prefs.pettingEnabled) }
    var hold by rememberSaveable { mutableFloatStateOf(prefs.dragHoldMs.toFloat()) }
    var x by rememberSaveable { mutableFloatStateOf(prefs.defaultX) }
    var y by rememberSaveable { mutableFloatStateOf(prefs.defaultY) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(.9f).padding(14.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(22.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("用你习惯的方式互动", style = MaterialTheme.typography.titleLarge)
                Text("单击小狗", style = MaterialTheme.typography.titleMedium)
                GestureOptions(tap, listOf("pet" to "摸摸", "wave" to "打招呼", "cuddle" to "抱抱")) { tap = it }
                Text("双击小狗", style = MaterialTheme.typography.titleMedium)
                Text("双击打开互动菜单，锁定位置时也可以使用。", style = MaterialTheme.typography.bodyMedium)
                Row { Text("识别轻抚动作", modifier = Modifier.weight(1f)); Switch(petting, { petting = it }) }
                Text("长按多久开始拖动", style = MaterialTheme.typography.titleMedium)
                Text("${hold.toInt()} 毫秒后可以拖动。轻抚时直接移动手指即可。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(hold, { hold = it }, valueRange = 300f..900f, steps = 11, modifier = Modifier.semantics { contentDescription = "长按拖动等待时间" })
                HorizontalDivider()
                Text("默认出现位置", style = MaterialTheme.typography.titleMedium)
                Text("屏幕横向 · ${(x * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                Slider(x, { x = it }, valueRange = 0f..1f, modifier = Modifier.semantics { contentDescription = "默认位置横向" })
                Text("屏幕纵向 · ${(y * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                Slider(y, { y = it }, valueRange = 0f..1f, modifier = Modifier.semantics { contentDescription = "默认位置纵向" })
                TextButton(onClick = { x = prefs.xFraction; y = prefs.yFraction }) { Text("使用小狗当前的位置") }
                Button(onClick = { prefs.tapAction = tap; prefs.pettingEnabled = petting; prefs.dragHoldMs = hold.toInt(); prefs.defaultX = x; prefs.defaultY = y; context.message("互动方式已保存。"); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("保存设置") }
                OutlinedButton(onClick = { prefs.defaultX = x; prefs.defaultY = y; OverlayService.resetPosition(context); context.message("已恢复到默认位置。") }, modifier = Modifier.fillMaxWidth()) { Text("立即恢复默认位置") }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("取消") }
            }
        }
    }
}

@Composable
private fun GestureOptions(current: String, options: List<Pair<String, String>>, choose: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { options.forEach { (value, title) -> FilterChip(current == value, { choose(value) }, label = { Text(title) }) } }
}

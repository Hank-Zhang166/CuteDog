package com.cutedog.app.ui

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cutedog.app.pet.PetSettings
import kotlin.math.hypot

internal data class PetRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun asRect() = RectF(left, top, right, bottom)
    fun encode() = "$left,$top,$right,$bottom"
    companion object {
        fun from(value: RectF) = PetRegion(value.left, value.top, value.right, value.bottom)
        fun decode(value: String): PetRegion { val p = value.split(',').map(String::toFloat); return PetRegion(p[0], p[1], p[2], p[3]) }
    }
}

/** Pure constrained geometry shared by dragging and accessible sliders. */
internal fun moveRegion(region: PetRegion, dx: Float, dy: Float, corner: Int): PetRegion {
    val minimum = .08f
    return when (corner) {
        0 -> region.copy(left = (region.left + dx).coerceIn(0f, region.right - minimum), top = (region.top + dy).coerceIn(0f, region.bottom - minimum))
        1 -> region.copy(right = (region.right + dx).coerceIn(region.left + minimum, 1f), top = (region.top + dy).coerceIn(0f, region.bottom - minimum))
        2 -> region.copy(right = (region.right + dx).coerceIn(region.left + minimum, 1f), bottom = (region.bottom + dy).coerceIn(region.top + minimum, 1f))
        3 -> region.copy(left = (region.left + dx).coerceIn(0f, region.right - minimum), bottom = (region.bottom + dy).coerceIn(region.top + minimum, 1f))
        else -> { val x = dx.coerceIn(-region.left, 1 - region.right); val y = dy.coerceIn(-region.top, 1 - region.bottom); region.copy(left = region.left + x, right = region.right + x, top = region.top + y, bottom = region.bottom + y) }
    }
}

@Composable
internal fun PetRegionEditor(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PetSettings(context) }
    var activeValue by rememberSaveable { mutableStateOf(PetRegion.from(prefs.activityRect).encode()) }
    var forbiddenValue by rememberSaveable { mutableStateOf(prefs.forbiddenRects.joinToString(";") { PetRegion.from(it).encode() }) }
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    var confined by rememberSaveable { mutableStateOf(prefs.confinement == "region") }
    var error by remember { mutableStateOf<String?>(null) }
    val active = PetRegion.decode(activeValue)
    val forbidden = if (forbiddenValue.isBlank()) emptyList() else forbiddenValue.split(';').map(PetRegion::decode)
    val chosen = forbidden.getOrNull(selected) ?: active
    fun update(region: PetRegion) {
        if (selected < 0) activeValue = region.encode()
        else forbiddenValue = forbidden.toMutableList().also { it[selected] = region }.joinToString(";") { it.encode() }
    }
    val currentRegion by rememberUpdatedState(chosen)
    val currentUpdate by rememberUpdatedState<(PetRegion) -> Unit>({ update(it) })
    val green = MaterialTheme.colorScheme.primary
    val ground = MaterialTheme.colorScheme.surfaceVariant
    val red = MaterialTheme.colorScheme.error
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(.95f).padding(12.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(22.dp)) {
                Text("给小狗留一块天地", style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("绿色是活动区域，红色是避让区域。拖动区域可移动，拖动四角可缩放；也可以使用下方滑块。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("只在绿色区域内活动", modifier = Modifier.weight(1f)); Switch(confined, { confined = it }) }
                    Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.width(162.dp).fillMaxHeight().semantics { contentDescription = "活动范围示意图。可通过下方滑块调整边界。" }.pointerInput(selected) {
                            var corner = -1
                            detectDragGestures(onDragStart = { start ->
                                val chosen = currentRegion
                                val corners = listOf(Offset(chosen.left * size.width, chosen.top * size.height), Offset(chosen.right * size.width, chosen.top * size.height), Offset(chosen.right * size.width, chosen.bottom * size.height), Offset(chosen.left * size.width, chosen.bottom * size.height))
                                corner = corners.indices.minByOrNull { hypot((corners[it].x - start.x).toDouble(), (corners[it].y - start.y).toDouble()) } ?: -1
                                if (corner >= 0 && (corners[corner] - start).getDistance() > 34.dp.toPx()) corner = -1
                            }, onDrag = { change, drag -> change.consume(); currentUpdate(moveRegion(currentRegion, drag.x / size.width, drag.y / size.height, corner)) })
                        }) {
                            drawRoundRect(ground, cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx()))
                            fun paint(region: PetRegion, color: Color, focus: Boolean) {
                                val origin = Offset(region.left * size.width, region.top * size.height)
                                val extent = Size((region.right - region.left) * size.width, (region.bottom - region.top) * size.height)
                                drawRect(color.copy(alpha = .18f), origin, extent); drawRect(color, origin, extent, style = Stroke(if (focus) 3.dp.toPx() else 1.dp.toPx()))
                                if (focus) listOf(origin, Offset(origin.x + extent.width, origin.y), origin + Offset(extent.width, extent.height), Offset(origin.x, origin.y + extent.height)).forEach { drawCircle(color, 5.dp.toPx(), it) }
                            }
                            paint(active, green, selected < 0)
                            forbidden.forEachIndexed { index, region -> paint(region, red, selected == index) }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        FilterChip(selected < 0, { selected = -1 }, label = { Text("活动区域") })
                        forbidden.forEachIndexed { index, _ -> FilterChip(selected == index, { selected = index }, label = { Text("避让 ${index + 1}") }) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { forbiddenValue = (forbidden + PetRegion(.3f, .35f, .7f, .55f)).joinToString(";") { it.encode() }; selected = forbidden.size }, enabled = forbidden.size < 8) { Text("＋ 避让区域") }
                        if (selected >= 0) TextButton(onClick = { forbiddenValue = forbidden.filterIndexed { index, _ -> index != selected }.joinToString(";") { it.encode() }; selected = -1 }) { Text("删除所选") }
                    }
                    RegionSlider("左边界", chosen.left, 0f..(chosen.right - .08f)) { update(chosen.copy(left = it)) }
                    RegionSlider("右边界", chosen.right, (chosen.left + .08f)..1f) { update(chosen.copy(right = it)) }
                    RegionSlider("上边界", chosen.top, 0f..(chosen.bottom - .08f)) { update(chosen.copy(top = it)) }
                    RegionSlider("下边界", chosen.bottom, (chosen.top + .08f)..1f) { update(chosen.copy(bottom = it)) }
                    Text("范围随屏幕尺寸调整；系统状态栏与导航区域会自动避让。无法读取第三方桌面的真实图标位置。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(onClick = {
                        try {
                            require(forbidden.none { it.left <= active.left && it.top <= active.top && it.right >= active.right && it.bottom >= active.bottom }) { "避让区域不能覆盖整个活动区域，请留一点活动空间。" }
                            prefs.activityRect = active.asRect(); prefs.forbiddenRects = forbidden.map { it.asRect() }; prefs.confinement = if (confined) "region" else "screen"
                            context.message("活动范围已保存。"); onDismiss()
                        } catch (e: Exception) { error = e.message ?: "无法保存活动范围。" }
                    }, modifier = Modifier.weight(1f)) { Text("保存范围") }
                }
            }
        }
    }
}

@Composable
private fun RegionSlider(label: String, value: Float, bounds: ClosedFloatingPointRange<Float>, update: (Float) -> Unit) {
    Column { Text("$label · ${(value * 100).toInt()}%", style = MaterialTheme.typography.labelLarge); Slider(value.coerceIn(bounds.start, bounds.endInclusive), update, valueRange = bounds, modifier = Modifier.semantics { contentDescription = label }) }
}

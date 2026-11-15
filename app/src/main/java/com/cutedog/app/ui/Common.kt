package com.cutedog.app.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun formatTime(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("M月d日 E HH:mm", Locale.CHINESE))
private val messageHandler = Handler(Looper.getMainLooper())
internal fun Context.message(text: String) {
    val app = applicationContext
    if (Looper.myLooper() == Looper.getMainLooper()) Toast.makeText(app, text, Toast.LENGTH_LONG).show()
    else messageHandler.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
}

@Composable
internal fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(top = 8.dp))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
internal fun SoftCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun EmptyNote(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 26.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun NavGlyph(index: Int, active: Boolean) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(23.dp)) {
        val w = size.width; val h = size.height; val line = Stroke(1.8.dp.toPx())
        when (index) {
            0 -> {
                val p = Path().apply { moveTo(w * .13f, h * .43f); lineTo(w * .5f, h * .1f); lineTo(w * .87f, h * .43f); lineTo(w * .87f, h * .88f); lineTo(w * .61f, h * .88f); lineTo(w * .61f, h * .62f); lineTo(w * .39f, h * .62f); lineTo(w * .39f, h * .88f); lineTo(w * .13f, h * .88f); close() }
                drawPath(p, color, style = line)
            }
            1 -> {
                drawRoundRect(color, Offset(w * .15f, h * .1f), androidx.compose.ui.geometry.Size(w * .7f, h * .8f), androidx.compose.ui.geometry.CornerRadius(w * .1f), style = line)
                for (n in 0..2) { val y = h * (.3f + n * .2f); drawLine(color, Offset(w * .34f, y), Offset(w * .68f, y), 1.6.dp.toPx()) }
            }
            2 -> {
                val p = Path().apply { moveTo(w * .18f, h * .14f); lineTo(w * .83f, h * .14f); lineTo(w * .83f, h * .72f); lineTo(w * .4f, h * .72f); lineTo(w * .2f, h * .9f); lineTo(w * .2f, h * .72f); lineTo(w * .12f, h * .72f); lineTo(w * .12f, h * .2f); close() }
                drawPath(p, color, style = line)
                for (n in 0..2) drawCircle(color, w * .035f, Offset(w * (.3f + n * .17f), h * .43f))
            }
            else -> {
                drawOval(color, Offset(w * .3f, h * .48f), androidx.compose.ui.geometry.Size(w * .43f, h * .36f), style = line)
                listOf(.17f to .4f, .35f to .2f, .62f to .2f, .82f to .4f).forEach { (x,y) -> drawCircle(color, w * .09f, Offset(w * x, h * y), style = line) }
            }
        }
    }
}

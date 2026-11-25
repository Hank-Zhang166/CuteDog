package com.cutedog.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun PawMark(modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSecondaryContainer
    Canvas(modifier.background(MaterialTheme.colorScheme.secondaryContainer, CircleShape).padding(7.dp)) {
        val w = size.width
        val h = size.height
        drawOval(ink, Offset(.24f * w, .51f * h), Size(.54f * w, .38f * h))
        drawOval(ink, Offset(.04f * w, .35f * h), Size(.18f * w, .25f * h))
        drawOval(ink, Offset(.25f * w, .13f * h), Size(.20f * w, .31f * h))
        drawOval(ink, Offset(.53f * w, .12f * h), Size(.20f * w, .31f * h))
        drawOval(ink, Offset(.78f * w, .34f * h), Size(.18f * w, .25f * h))
    }
}

@Composable
internal fun ChatBubble(
    speaker: String, text: String, user: Boolean, modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null, deleteTag: String = "",
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!user) PawMark(Modifier.size(31.dp))
            Text(speaker, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            if (onDelete != null) {
                Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(42.dp).testTag(deleteTag)
                        .semantics { contentDescription = "这条消息的操作" }) {
                        Text("···", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("删除消息") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(topStart = if (user) 22.dp else 5.dp, topEnd = if (user) 5.dp else 22.dp,
                bottomStart = 22.dp, bottomEnd = 22.dp),
            color = if (user) colors.primaryContainer else colors.secondaryContainer,
            contentColor = if (user) colors.onPrimaryContainer else colors.onSecondaryContainer,
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            SelectionContainer { Text(text, Modifier.padding(horizontal = 17.dp, vertical = 11.dp),
                style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

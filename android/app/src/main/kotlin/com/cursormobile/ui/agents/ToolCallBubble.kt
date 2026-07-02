package com.cursormobile.ui.agents

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursormobile.data.db.MessageEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun ToolCallBubble(m: MessageEntity, modifier: Modifier = Modifier) {
    var expanded by remember(m.id) { mutableStateOf(false) }
    val status = m.toolStatus ?: "completed"
    val (statusLabel, statusColor) = when (status) {
        "running" -> "running" to MaterialTheme.colorScheme.primary
        "error" -> "error" to MaterialTheme.colorScheme.error
        else -> "done" to MaterialTheme.colorScheme.secondary
    }
    val preview = remember(m.toolInput, m.text) { toolPreview(m.toolName ?: m.text, m.toolInput) }

    Column(
        modifier
            .widthIn(max = 340.dp)
            .animateContentSize()
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f), MaterialTheme.shapes.medium)
            .clickable { expanded = !expanded }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(toolIcon(m.toolName ?: m.text), null, tint = MaterialTheme.colorScheme.secondary)
            Column(Modifier.weight(1f)) {
                Text(m.toolName ?: m.text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (expanded) Int.MAX_VALUE else 2)
            }
            Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = statusColor)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        if (expanded) {
            m.toolInput?.takeIf { it.isNotBlank() }?.let { input ->
                Text("Input", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                Text(formatJson(input), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
            }
            m.toolOutput?.takeIf { it.isNotBlank() }?.let { output ->
                Text("Output", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                Text(formatJson(output), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private fun toolIcon(name: String): ImageVector = when {
    name.contains("shell", true) || name.contains("terminal", true) -> Icons.Outlined.Terminal
    name.contains("grep", true) || name.contains("search", true) || name.contains("glob", true) -> Icons.Outlined.Search
    name.contains("write", true) || name.contains("edit", true) || name.contains("replace", true) -> Icons.Outlined.Description
    name.contains("read", true) -> Icons.Outlined.Code
    else -> Icons.Outlined.Build
}

private fun toolPreview(name: String, inputJson: String?): String {
    if (inputJson.isNullOrBlank()) return name
    return runCatching {
        val obj = Json.parseToJsonElement(inputJson).jsonObject
        when {
            obj["path"] != null -> "${name}: ${obj["path"]!!.jsonPrimitive.content}"
            obj["file_path"] != null -> "${name}: ${obj["file_path"]!!.jsonPrimitive.content}"
            obj["command"] != null -> "${name}: ${obj["command"]!!.jsonPrimitive.content.take(80)}"
            obj["globPattern"] != null -> "${name}: ${obj["globPattern"]!!.jsonPrimitive.content}"
            obj["pattern"] != null -> "${name}: ${obj["pattern"]!!.jsonPrimitive.content}"
            else -> "$name · ${inputJson.take(100)}"
        }
    }.getOrDefault(inputJson.take(100))
}

private fun formatJson(raw: String): String {
    return runCatching {
        val el = Json.parseToJsonElement(raw)
        if (el is JsonObject) {
            el.entries.joinToString("\n") { (k, v) ->
                val value = v.jsonPrimitive.contentOrNull() ?: v.toString()
                "$k: ${value.take(1200)}"
            }
        } else raw
    }.getOrDefault(raw.take(2000))
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    runCatching { content }.getOrNull()

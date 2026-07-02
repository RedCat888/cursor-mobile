package com.cursormobile.ui.agents

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.cursormobile.data.net.IdeComposerMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun IdeToolCard(m: IdeComposerMessage, modifier: Modifier = Modifier) {
    var expanded by remember(m.bubbleId) { mutableStateOf(false) }
    val status = m.toolStatus ?: "completed"
    val running = status.equals("running", true) || status.equals("loading", true)
    val failed = status.equals("error", true) || status.equals("failed", true)
    val name = m.toolName ?: "tool"
    val parsed = remember(m.toolInput, m.toolOutput, name) { parseToolPayload(name, m.toolInput, m.toolOutput) }

    Surface(
        modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { expanded = !expanded },
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    toolIcon(name),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        parsed.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        parsed.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                    )
                }
                when {
                    running -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    failed -> Text("failed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    else -> Text("done", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }

            when (parsed.kind) {
                ToolKind.Terminal -> TerminalPreview(parsed.primary, expanded)
                ToolKind.Edit -> EditPreview(parsed.primary, parsed.secondary, expanded)
                ToolKind.Search -> SearchPreview(parsed.primary)
                ToolKind.Generic -> Unit
            }

            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                m.toolInput?.takeIf { it.isNotBlank() }?.let { input ->
                    Text("Input", style = MaterialTheme.typography.labelMedium)
                    Text(
                        formatJson(input),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                m.toolOutput?.takeIf { it.isNotBlank() }?.let { output ->
                    Text("Output", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    Text(
                        formatJson(output),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

private enum class ToolKind { Terminal, Edit, Search, Generic }

private data class ParsedTool(
    val kind: ToolKind,
    val title: String,
    val subtitle: String,
    val primary: String = "",
    val secondary: String = "",
)

@Composable
private fun TerminalPreview(command: String, expanded: Boolean) {
    if (command.isBlank()) return
    Text(
        "$ $command",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f), MaterialTheme.shapes.small)
            .padding(10.dp),
    )
}

@Composable
private fun EditPreview(path: String, snippet: String, expanded: Boolean) {
    if (path.isBlank() && snippet.isBlank()) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
            .padding(10.dp),
    ) {
        if (path.isNotBlank()) {
            Text(path, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
        }
        if (snippet.isNotBlank()) {
            Text(
                snippet,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SearchPreview(query: String) {
    if (query.isBlank()) return
    Text(
        query,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f), MaterialTheme.shapes.small)
            .padding(10.dp),
    )
}

private fun parseToolPayload(name: String, inputJson: String?, outputJson: String?): ParsedTool {
    val title = formatToolName(name)
    val lower = name.lowercase()
    val kind = when {
        lower.contains("shell") || lower.contains("terminal") || lower.contains("run_terminal") -> ToolKind.Terminal
        lower.contains("write") || lower.contains("edit") || lower.contains("replace") || lower.contains("read_file") ->
            ToolKind.Edit
        lower.contains("grep") || lower.contains("search") || lower.contains("glob") -> ToolKind.Search
        else -> ToolKind.Generic
    }

    inputJson?.let {
        runCatching {
            val obj = Json.parseToJsonElement(it).jsonObject
            val command = obj["command"]?.jsonPrimitive?.content
            val path = obj["path"]?.jsonPrimitive?.content ?: obj["targetFile"]?.jsonPrimitive?.content
            val query = obj["globPattern"]?.jsonPrimitive?.content ?: obj["pattern"]?.jsonPrimitive?.content
            val oldStr = obj["old_string"]?.jsonPrimitive?.content
            val newStr = obj["new_string"]?.jsonPrimitive?.content

            return when (kind) {
                ToolKind.Terminal -> ParsedTool(
                    kind,
                    title,
                    command?.take(72) ?: "Running command",
                    primary = command ?: "",
                )
                ToolKind.Edit -> ParsedTool(
                    kind,
                    title,
                    path ?: "Editing file",
                    primary = path ?: "",
                    secondary = listOfNotNull(oldStr, newStr).joinToString("\n").take(400),
                )
                ToolKind.Search -> ParsedTool(
                    kind,
                    title,
                    query ?: path ?: name,
                    primary = query ?: path ?: "",
                )
                ToolKind.Generic -> ParsedTool(kind, title, path ?: command ?: name)
            }
        }
    }

    return ParsedTool(
        kind,
        title,
        if (outputJson != null && outputJson.length > 4) "Output ready" else name,
    )
}

private fun formatToolName(raw: String): String = raw
    .replace("_", " ")
    .replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
    .split(" ")
    .joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }

private fun toolIcon(name: String): ImageVector = when {
    name.contains("shell", true) || name.contains("terminal", true) || name.contains("run_terminal", true) ->
        Icons.Outlined.Terminal
    name.contains("grep", true) || name.contains("search", true) || name.contains("glob", true) ->
        Icons.Outlined.Search
    name.contains("write", true) || name.contains("edit", true) || name.contains("replace", true) ||
        name.contains("read_file", true) || name.contains("file", true) ->
        Icons.Outlined.Description
    name.contains("code", true) -> Icons.Outlined.Code
    else -> Icons.Outlined.Build
}

private fun formatJson(raw: String): String = runCatching {
    val el = Json.parseToJsonElement(raw)
    if (el is JsonObject) {
        el.entries.joinToString("\n") { (k, v) ->
            val value = v.jsonPrimitive.contentOrNull() ?: v.toString()
            "$k: ${value.take(1600)}"
        }
    } else raw.take(2000)
}.getOrDefault(raw.take(2000))

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    runCatching { content }.getOrNull()

package com.cursormobile.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        renderMarkdownBlocks(text, isStreaming).forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyMedium)
                is MdBlock.Heading -> Text(
                    block.text,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                is MdBlock.Code -> Text(
                    block.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            MaterialTheme.shapes.small,
                        )
                        .padding(12.dp),
                )
                is MdBlock.Table -> TableBlock(block)
            }
        }
    }
}

@Composable
private fun TableBlock(block: MdBlock.Table) {
    if (block.rows.isEmpty()) return
    val scroll = rememberScrollState()
    val colCount = block.rows.maxOf { it.size }
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.surfaceContainerHighest

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .border(1.dp, borderColor, MaterialTheme.shapes.small),
    ) {
        Column(Modifier.height(IntrinsicSize.Min)) {
            block.rows.forEachIndexed { rowIdx, row ->
                val isHeader = rowIdx == 0
                Row(Modifier.height(IntrinsicSize.Min)) {
                    for (colIdx in 0 until colCount) {
                        val cell = row.getOrElse(colIdx) { "" }
                        Box(
                            Modifier
                                .width(120.dp)
                                .fillMaxHeight()
                                .background(if (isHeader) headerBg else MaterialTheme.colorScheme.surface)
                                .border(0.5.dp, borderColor)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                cell,
                                style = if (isHeader) {
                                    MaterialTheme.typography.labelMedium
                                } else {
                                    MaterialTheme.typography.bodySmall
                                },
                                fontFamily = if (isHeader) FontFamily.Default else FontFamily.Monospace,
                                fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

private sealed class MdBlock {
    data class Paragraph(val text: AnnotatedString) : MdBlock()
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Code(val text: String) : MdBlock()
    data class Table(val rows: List<List<String>>) : MdBlock()
}

private fun isTableSeparator(cells: List<String>): Boolean =
    cells.isNotEmpty() && cells.all { cell -> cell.all { c -> c == '-' || c == ':' || c == ' ' || c == '|' } }

private fun renderMarkdownBlocks(raw: String, isStreaming: Boolean): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = raw.replace("\r\n", "\n").split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.startsWith("```") -> {
                val buf = StringBuilder()
                i++
                while (i < lines.size && !lines[i].startsWith("```")) {
                    buf.appendLine(lines[i])
                    i++
                }
                blocks.add(MdBlock.Code(buf.toString().trimEnd()))
            }
            line.trimStart().startsWith("|") && line.contains("|") -> {
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    val row = lines[i].trim().trim('|').split("|").map { it.trim() }
                    if (!isTableSeparator(row)) rows.add(row)
                    i++
                }
                if (rows.isNotEmpty()) blocks.add(MdBlock.Table(rows))
                continue
            }
            line.startsWith("### ") -> blocks.add(MdBlock.Heading(3, line.removePrefix("### ").formatInline()))
            line.startsWith("## ") -> blocks.add(MdBlock.Heading(2, line.removePrefix("## ").formatInline()))
            line.startsWith("# ") -> blocks.add(MdBlock.Heading(1, line.removePrefix("# ").formatInline()))
            line.isBlank() -> Unit
            else -> {
                val para = StringBuilder(line.formatInline())
                i++
                while (
                    i < lines.size &&
                    lines[i].isNotBlank() &&
                    !lines[i].startsWith("#") &&
                    !lines[i].startsWith("```") &&
                    !lines[i].trimStart().startsWith("|")
                ) {
                    para.append("\n").append(lines[i].formatInline())
                    i++
                }
                blocks.add(MdBlock.Paragraph(inlineMarkdown(para.toString())))
                continue
            }
        }
        i++
    }
    if (isStreaming && blocks.isEmpty() && raw.isNotBlank()) {
        blocks.add(MdBlock.Paragraph(inlineMarkdown(raw)))
    }
    if (isStreaming && blocks.isNotEmpty()) {
        val last = blocks.last()
        if (last is MdBlock.Paragraph) {
            blocks[blocks.lastIndex] = MdBlock.Paragraph(
                buildAnnotatedString {
                    append(last.text)
                    append(" ▍")
                },
            )
        }
    }
    return blocks
}

private fun String.formatInline(): String = replace(Regex("\\*\\*(.+?)\\*\\*")) { it.groupValues[1] }
    .replace(Regex("`([^`]+)`")) { it.groupValues[1] }

private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var idx = 0
    val combined = Regex("(\\*\\*.+?\\*\\*|`[^`]+`)")
    combined.findAll(text).forEach { match ->
        append(text.substring(idx, match.range.first))
        when {
            match.value.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                append(match.value.removeSurrounding("**"))
            }
            match.value.startsWith("`") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                append(match.value.removeSurrounding("`"))
            }
        }
        idx = match.range.last + 1
    }
    append(text.substring(idx))
}

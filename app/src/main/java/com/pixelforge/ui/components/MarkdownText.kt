package com.pixelforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelforge.workspace.Workspace

private sealed class Block {
    data class Code(val text: String, val title: String?) : Block()
    data class Heading(val level: Int, val text: String) : Block()
    data class Bullet(val indent: Int, val marker: String, val text: String) : Block()
    data class Quote(val text: String) : Block()
    data class Para(val text: String) : Block()
    object Rule : Block()
}

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^(\\s*)([-*•]|\\d+[.)])\\s+(.*)$")
private val RULE = Regex("^(-{3,}|\\*{3,}|_{3,})$")
private val TABLE_SEP = Regex("^\\|?[\\s:|-]+\\|?$")
private val INLINE = Regex("\\*\\*(.+?)\\*\\*|`([^`\\n]+)`|(?<![*\\w])\\*([^*\\n]+)\\*(?![*\\w])")

private fun parseBlocks(src: String): List<Block> {
    val out = mutableListOf<Block>()
    for (seg in Workspace.parse(src)) {
        when (seg) {
            is Workspace.Companion.Segment.File ->
                out += Block.Code(seg.content.trimEnd(), "📄 " + seg.path + if (seg.complete) "" else " ⚠ terpotong")
            is Workspace.Companion.Segment.Text -> parseMarkdown(seg.text, out)
        }
    }
    return out
}

private fun parseMarkdown(src: String, out: MutableList<Block>) {
    val lines = src.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        if (t.startsWith("```")) {
            val code = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                code.append(lines[i]).append('\n')
                i++
            }
            out += Block.Code(code.toString().trimEnd(), null)
            i++
            continue
        }
        val h = HEADING.matchEntire(t)
        val b = BULLET.matchEntire(line)
        if (t.isEmpty()) {
            // lewati baris kosong; jarak antar blok diatur saat render
        } else if (h != null) {
            out += Block.Heading(h.groupValues[1].length, h.groupValues[2])
        } else if (RULE.matches(t)) {
            out += Block.Rule
        } else if (t.startsWith("|")) {
            if (!TABLE_SEP.matches(t)) {
                val cells = t.trim('|').split('|').map { it.trim() }
                out += Block.Para(cells.joinToString("  •  "))
            }
        } else if (t.startsWith(">")) {
            out += Block.Quote(t.removePrefix(">").trim())
        } else if (b != null) {
            val m = b.groupValues[2]
            out += Block.Bullet(b.groupValues[1].length / 2, if (m.first().isDigit()) m else "•", b.groupValues[3])
        } else {
            out += Block.Para(t)
        }
        i++
    }
}

private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in INLINE.findAll(s)) {
        append(s.substring(last, m.range.first))
        when {
            m.groups[1] != null ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
            m.groups[2] != null ->
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x33FFFFFF))) {
                    append(m.groupValues[2])
                }
            else ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groupValues[3]) }
        }
        last = m.range.last + 1
    }
    append(s.substring(last))
}

/** Render Markdown ringan (judul, tebal, kode, daftar, kutipan, tabel) tanpa tanda-tanda mentah. */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    fontSize: TextUnit = 14.sp
) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier) {
        blocks.forEach { b ->
            when (b) {
                is Block.Code -> Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    if (b.title != null) {
                        Text(b.title, color = Color(0xFFFFAB40), fontSize = (fontSize.value - 2).sp)
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0D1117), RoundedCornerShape(6.dp))
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp)
                    ) {
                        Text(
                            b.text,
                            color = Color(0xFFE6EDF3),
                            fontFamily = FontFamily.Monospace,
                            fontSize = (fontSize.value - 2).sp,
                            softWrap = false
                        )
                    }
                }
                is Block.Heading -> Text(
                    inline(b.text),
                    color = if (b.level <= 2) Color(0xFF00E5FF) else color,
                    fontWeight = FontWeight.Bold,
                    fontSize = (fontSize.value + (7 - b.level).coerceAtMost(5)).sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                )
                is Block.Bullet -> Row(Modifier.padding(start = (b.indent * 12).dp, top = 2.dp)) {
                    Text(b.marker + " ", color = color, fontSize = fontSize)
                    Text(inline(b.text), color = color, fontSize = fontSize)
                }
                is Block.Quote -> Text(
                    inline(b.text),
                    color = Color(0xFFB0BEC5),
                    fontStyle = FontStyle.Italic,
                    fontSize = fontSize,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                )
                is Block.Para -> Text(
                    inline(b.text),
                    color = color,
                    fontSize = fontSize,
                    modifier = Modifier.padding(top = 4.dp)
                )
                is Block.Rule -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = Color(0x44FFFFFF)
                )
            }
        }
    }
}

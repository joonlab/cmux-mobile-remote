package kr.joonlab.core

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 대화 본문용 작은 마크다운 렌더러(웹 뷰어 renderMarkdownHtml 의 규칙을 옮김 — VIEWER-INVENTORY 1-G).
 * - **setext 제목은 만들지 않는다**: «문장\n---» 는 문단 + 가로줄이다(웹 뷰어가 915곳에서 본문을 큰 제목으로 그리던 사고).
 * - 표는 가로 스크롤 상자에 넣는다(칸을 억지로 좁히지 않는다).
 * - 원문 HTML 은 해석하지 않고 글자 그대로 둔다 — 웹의 sanitize 에 해당.
 */
sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Para(val text: String) : MdBlock()
    data class Code(val lang: String, val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class ListItem(val indent: Int, val marker: String, val text: String) : MdBlock()
    data class Table(val head: List<String>, val rows: List<List<String>>) : MdBlock()
    data object Rule : MdBlock()
}

private val FENCE = Regex("^\\s*(```|~~~)\\s*([\\w+#.-]*)")
private val HEAD = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
private val TSEP = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
private val QUOTE = Regex("^\\s*>\\s?(.*)$")
private val LIST = Regex("^(\\s*)([-*+]|\\d{1,3}[.)])\\s+(.*)$")

fun parseMarkdown(src: String): List<MdBlock> {
    val lines = src.replace("\r\n", "\n").split("\n")
    val out = mutableListOf<MdBlock>()
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out.add(MdBlock.Para(para.toString().trimEnd())); para.setLength(0) }
    var i = 0
    while (i < lines.size) {
        val l = lines[i]
        val fence = FENCE.find(l)
        if (fence != null) {
            flush()
            val mark = fence.groupValues[1]
            val body = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith(mark)) { body.appendLine(lines[i]); i++ }
            out.add(MdBlock.Code(fence.groupValues[2], body.toString().trimEnd('\n')))
            i++; continue
        }
        if (l.isBlank()) { flush(); i++; continue }
        val h = HEAD.find(l)
        if (h != null) { flush(); out.add(MdBlock.Heading(h.groupValues[1].length, h.groupValues[2])); i++; continue }
        if (RULE.matches(l)) { flush(); out.add(MdBlock.Rule); i++; continue }
        if (l.contains('|') && i + 1 < lines.size && TSEP.matches(lines[i + 1])) {
            flush()
            val head = cells(l)
            val rows = mutableListOf<List<String>>()
            i += 2
            while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) { rows.add(cells(lines[i])); i++ }
            out.add(MdBlock.Table(head, rows)); continue
        }
        val q = QUOTE.find(l)
        if (q != null) {
            flush()
            val sb = StringBuilder(q.groupValues[1])
            i++
            while (i < lines.size) { val m = QUOTE.find(lines[i]) ?: break; sb.append('\n').append(m.groupValues[1]); i++ }
            out.add(MdBlock.Quote(sb.toString())); continue
        }
        val li = LIST.find(l)
        if (li != null) {
            flush()
            val indent = li.groupValues[1].replace("\t", "    ").length / 2
            val m = li.groupValues[2]
            val sb = StringBuilder(li.groupValues[3])
            i++
            // 들여쓴 이어지는 줄은 같은 항목에 붙인다
            while (i < lines.size && lines[i].isNotBlank() && lines[i].startsWith("  ") && LIST.find(lines[i]) == null) {
                sb.append('\n').append(lines[i].trim()); i++
            }
            out.add(MdBlock.ListItem(indent.coerceAtMost(4), if (m[0].isDigit()) m else "•", sb.toString())); continue
        }
        if (para.isNotEmpty()) para.append('\n')
        para.append(l)
        i++
    }
    flush()
    return out
}

private fun cells(l: String): List<String> {
    var s = l.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
    return s.split(Regex("(?<!\\\\)\\|")).map { it.trim().replace("\\|", "|") }
}

private val INLINE = Regex(
    "(?<code>`+)(?<codeT>.+?)\\k<code>" +
        "|\\*\\*(?<bold>.+?)\\*\\*" +
        "|~~(?<strike>.+?)~~" +
        "|\\[(?<linkT>[^\\]]+)]\\((?<linkU>https?://[^)\\s]+)\\)" +
        "|(?<![\\w*])\\*(?![\\s*])(?<ital>.+?)(?<![\\s*])\\*(?![\\w*])" +
        "|(?<url>https?://[^\\s<>()\\[\\]`]+[^\\s<>()\\[\\]`.,;:!?'\"])"
)

/** 줄 안의 강조·코드·링크 → AnnotatedString. 굵게·기울임은 안쪽을 다시 해석한다. */
fun inlineMarkdown(s: String, accent: Color, codeBg: Color): AnnotatedString = buildAnnotatedString { appendInline(s, accent, codeBg) }

private fun AnnotatedString.Builder.appendInline(s: String, accent: Color, codeBg: Color) {
    var at = 0
    for (m in INLINE.findAll(s)) {
        if (m.range.first > at) append(s.substring(at, m.range.first))
        val g = m.groups
        when {
            g["codeT"] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 0.92.em())) {
                append(g["codeT"]!!.value)
            }
            g["bold"] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(g["bold"]!!.value, accent, codeBg) }
            g["strike"] != null -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(g["strike"]!!.value, accent, codeBg) }
            g["ital"] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(g["ital"]!!.value, accent, codeBg) }
            g["linkT"] != null -> withLink(LinkAnnotation.Url(g["linkU"]!!.value, linkStyle(accent))) { append(g["linkT"]!!.value) }
            g["url"] != null -> withLink(LinkAnnotation.Url(g["url"]!!.value, linkStyle(accent))) { append(g["url"]!!.value) }
        }
        at = m.range.last + 1
    }
    if (at < s.length) append(s.substring(at))
}

private fun Double.em() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)
private fun linkStyle(accent: Color) = TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline))

/**
 * 마크다운 본문. 색은 주입된 팔레트에서 — 본문 color, 코드 바탕 panel2, 링크 accent, 선 border.
 * 파싱은 text 가 바뀔 때만 한다(대화 목록이 다시 그려질 때마다 파싱하지 않는다).
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, color: Color = LocalPalette.current.text,
                 fontSize: TextUnit = 14.sp, lineHeight: TextUnit = 22.sp) {
    val c = LocalPalette.current
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in blocks) when (b) {
            is MdBlock.Heading -> Text(inlineMarkdown(b.text, c.accent, c.panel2), color = color, fontWeight = FontWeight.Bold,
                fontSize = fontSize * when (b.level) { 1 -> 1.3f; 2 -> 1.2f; 3 -> 1.1f; else -> 1f },
                lineHeight = lineHeight * when (b.level) { 1 -> 1.3f; 2 -> 1.2f; else -> 1.1f },
                modifier = Modifier.padding(top = 4.dp))
            is MdBlock.Para -> Text(inlineMarkdown(b.text, c.accent, c.panel2), color = color, fontSize = fontSize, lineHeight = lineHeight)
            is MdBlock.Code -> Box(Modifier.fillMaxWidth().background(c.panel2, RoundedCornerShape(8.dp))
                .border(1.dp, c.border, RoundedCornerShape(8.dp)).horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(b.text, color = color, fontFamily = FontFamily.Monospace, fontSize = fontSize * 0.86f, lineHeight = lineHeight * 0.86f,
                    softWrap = false)
            }
            is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(c.border))
                Text(inlineMarkdown(b.text, c.accent, c.panel2), color = c.dim, fontSize = fontSize, lineHeight = lineHeight,
                    modifier = Modifier.padding(start = 10.dp))
            }
            is MdBlock.ListItem -> Row(Modifier.padding(start = (b.indent * 14).dp)) {
                Text(b.marker, color = c.dim, fontSize = fontSize, lineHeight = lineHeight,
                    modifier = Modifier.width(if (b.marker == "•") 16.dp else 26.dp))
                Text(inlineMarkdown(b.text, c.accent, c.panel2), color = color, fontSize = fontSize, lineHeight = lineHeight)
            }
            is MdBlock.Table -> MdTable(b, color, fontSize, lineHeight)
            MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(c.border))
        }
    }
}

/** 표 — 칸 폭은 글자 수로 어림하고(한글은 두 칸), 넘치면 가로로 민다. */
@Composable
private fun MdTable(t: MdBlock.Table, color: Color, fontSize: TextUnit, lineHeight: TextUnit) {
    val c = LocalPalette.current
    val n = maxOf(t.head.size, t.rows.maxOfOrNull { it.size } ?: 0)
    val widths = remember(t) {
        (0 until n).map { k ->
            val longest = (listOf(t.head) + t.rows).maxOf { r -> r.getOrNull(k)?.let(::displayLen) ?: 0 }
            (longest * 7 + 20).coerceIn(48, 300)
        }
    }
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(Modifier.border(1.dp, c.border, RoundedCornerShape(6.dp))) {
            (listOf(t.head) + t.rows).forEachIndexed { ri, r ->
                Row(Modifier.height(IntrinsicSize.Min).background(if (ri == 0) c.panel2 else Color.Transparent)) {
                    for (k in 0 until n) {
                        Text(inlineMarkdown(r.getOrNull(k) ?: "", c.accent, c.panel2), color = color,
                            fontSize = fontSize * 0.9f, lineHeight = lineHeight * 0.9f,
                            fontWeight = if (ri == 0) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.width(widths[k].dp).fillMaxHeight()
                                .border(0.5.dp, c.border).padding(horizontal = 8.dp, vertical = 5.dp))
                    }
                }
            }
        }
    }
}

private fun displayLen(s: String): Int = s.sumOf { ch -> if (ch.code >= 0x1100) 2 else 1 }

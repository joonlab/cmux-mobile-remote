package kr.joonlab.cmuxremote

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Raycast 스니펫(에이전트 /snippets = Raycast «Export Snippets» 원본)을 폰 입력칸에서 쓴다.
 *
 * 펼치는 규칙(Raycast 와 같게): 키워드를 다 치는 순간 펼친다. 단 그 키워드가 **다른 키워드의 앞부분**이면
 * (`;circ1` 과 `;circ10`) 바로 펼치면 긴 쪽을 영영 못 친다 → 다음 글자가 긴 키워드로 이어지지 않을 때 펼친다.
 * 펼친 직후엔 «되돌리기» 칩을 띄운다 — 의도치 않은 확장을 한 번에 되돌린다.
 */
object SnippetStore {
    var list by mutableStateOf<List<Snippet>>(emptyList())
    private var byKey: Map<String, Snippet> = emptyMap()
    private var keys: List<String> = emptyList()

    fun load(items: List<Snippet>) {
        list = items
        byKey = items.filter { !it.keyword.isNullOrBlank() }.associateBy { it.keyword!! }
        keys = byKey.keys.sortedByDescending { it.length }
    }

    private fun isFinal(k: String) = keys.none { it.length > k.length && it.startsWith(k) }

    /** 방금 한 글자를 쳤을 때 펼칠 키워드. (키워드, 키워드 뒤에 남길 글자) */
    fun match(beforeCursor: String): Pair<Snippet, String>? {
        for (k in keys) if (beforeCursor.endsWith(k) && isFinal(k)) return byKey[k]!! to ""
        if (beforeCursor.isEmpty()) return null
        val last = beforeCursor.last().toString()
        val head = beforeCursor.dropLast(1)
        for (k in keys) {
            if (head.endsWith(k) && keys.none { it.startsWith(k + last) }) return byKey[k]!! to last
        }
        return null
    }

    // 최근 쓴 스니펫 — 칩 줄에 먼저 보인다
    private const val PREF = "snippets"
    private val DEFAULT_RECENT = listOf(";cc", ";auq", ";work")
    private val HIDDEN_CHIPS = emptySet<String>()  // 칩 줄에서 뺄 키워드. 입력 중 자동 확장은 그대로
    fun recent(ctx: Context): List<Snippet> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("recent", null)
        val ks = raw?.split("\u001f")?.filter { it.isNotBlank() } ?: DEFAULT_RECENT
        return ks.filter { it !in HIDDEN_CHIPS }.mapNotNull { k -> byKey[k] ?: list.firstOrNull { it.name == k } }.take(8)
    }

    fun used(ctx: Context, s: Snippet) {
        val id = s.keyword ?: s.name
        val prev = recent(ctx).map { it.keyword ?: it.name }.filter { it != id }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("recent", (listOf(id) + prev).take(12).joinToString("\u001f")).apply()
    }
}

/** {argument name="x" options="a, b" default="y"} 하나. */
data class Arg(val name: String, val options: List<String>, val default: String?)

private val TOKEN = Regex("""\{(cursor|clipboard|date[^}]*|argument[^}]*)\}""")
private fun attr(src: String, key: String) = Regex("""$key\s*=\s*"([^"]*)"""").find(src)?.groupValues?.get(1)

fun argsOf(text: String): List<Arg> = TOKEN.findAll(text).map { it.groupValues[1] }
    .filter { it.startsWith("argument") }
    .map { a -> Arg(attr(a, "name") ?: "값", attr(a, "options")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: emptyList(), attr(a, "default")) }
    .distinctBy { it.name }.toList()

/** placeholder 를 채운다 → (펼친 글, 커서 위치(없으면 끝)). */
fun resolve(text: String, ctx: Context, args: Map<String, String>): Pair<String, Int?> {
    var cursor: Int? = null
    val sb = StringBuilder()
    var last = 0
    for (m in TOKEN.findAll(text)) {
        sb.append(text, last, m.range.first)
        val t = m.groupValues[1]
        when {
            t == "cursor" -> if (cursor == null) cursor = sb.length
            t == "clipboard" -> sb.append(
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: "")
            t.startsWith("date") -> {
                val fmt = attr(t, "format") ?: Regex(""""([^"]+)"""").find(t)?.groupValues?.get(1) ?: "yyyy-MM-dd"
                sb.append(runCatching { SimpleDateFormat(fmt, Locale.KOREA).format(Date()) }.getOrDefault(fmt))
            }
            t.startsWith("argument") -> sb.append(args[attr(t, "name") ?: "값"] ?: attr(t, "default") ?: "")
        }
        last = m.range.last + 1
    }
    sb.append(text, last, text.length)
    return sb.toString() to cursor
}

/** 스니펫을 value 의 [start, end) 자리에 넣는다. */
fun insertSnippet(value: TextFieldValue, start: Int, end: Int, body: String, cursorAt: Int?, tail: String = ""): TextFieldValue {
    val t = value.text
    val out = t.substring(0, start) + body + tail + t.substring(end)
    val pos = start + (cursorAt ?: (body.length + tail.length))
    return TextFieldValue(out, TextRange(pos))
}

/**
 * 스니펫이 붙은 입력칸. 키워드 자동 확장 + 되돌리기 칩 + {argument} 입력 창.
 * pendingInsert: 목록에서 고른 스니펫을 커서 자리에 넣을 때 바깥에서 부른다.
 */
class SnippetFieldState {
    var value by mutableStateOf(TextFieldValue(""))
    var undo by mutableStateOf<Pair<TextFieldValue, String>?>(null)   // (펼치기 전 값, 키워드)
    var askArgs by mutableStateOf<Pair<Snippet, (Map<String, String>) -> Unit>?>(null)

    fun clear() { value = TextFieldValue(""); undo = null }

    /** 목록·칩에서 고른 스니펫을 커서 자리에 넣는다. */
    fun insert(ctx: Context, s: Snippet) {
        val sel = value.selection
        apply(ctx, s, value, sel.min, sel.max, "", null)
    }

    fun apply(ctx: Context, s: Snippet, base: TextFieldValue, start: Int, end: Int, tail: String, before: TextFieldValue?) {
        val go = { args: Map<String, String> ->
            val (body, cur) = resolve(s.text, ctx, args)
            value = insertSnippet(base, start, end, body, cur, tail)
            undo = if (before != null && s.keyword != null) before to s.keyword else null
            SnippetStore.used(ctx, s)
        }
        if (argsOf(s.text).isNotEmpty()) askArgs = s to go else go(emptyMap())
    }

    fun onChange(ctx: Context, nv: TextFieldValue) {
        val old = value
        value = nv
        undo = null
        // 한 글자 늘었고 커서가 그 끝에 있을 때만 — 붙여넣기·삭제·커서 이동에는 반응하지 않는다
        if (nv.text.length != old.text.length + 1 || !nv.selection.collapsed) return
        val cur = nv.selection.start
        val m = SnippetStore.match(nv.text.substring(0, cur)) ?: return
        val (s, tail) = m
        val start = cur - tail.length - s.keyword!!.length
        apply(ctx, s, nv, start, cur, tail, nv)
    }
}

@Composable
fun SnippetField(st: SnippetFieldState, hint: String, modifier: Modifier = Modifier.fillMaxWidth(),
                 singleLine: Boolean = false, mono: Boolean = false) {
    val ctx = LocalContext.current
    Column(modifier) {
        Box(Modifier.fillMaxWidth().heightIn(min = 46.dp)
            .background(C.raised, RoundedCornerShape(14.dp))
            .border(1.dp, C.border, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (st.value.text.isEmpty()) Text(hint, color = C.dim, fontSize = 14.5.sp)
            BasicTextField(st.value, { st.onChange(ctx, it) }, singleLine = singleLine, maxLines = if (singleLine) 1 else 6,
                textStyle = TextStyle(color = C.text, fontSize = 14.5.sp, lineHeight = 21.sp,
                    fontFamily = if (mono) FontFamily.Monospace else null),
                cursorBrush = SolidColor(C.accent), modifier = Modifier.fillMaxWidth())
        }
        st.undo?.let { (prev, kw) ->
            Text("↶ $kw 되돌리기", color = C.accent, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp).clickable { st.value = prev; st.undo = null }.padding(4.dp))
        }
    }
    st.askArgs?.let { (s, go) -> ArgDialog(s, onDone = { st.askArgs = null; go(it) }, onDismiss = { st.askArgs = null }) }
}

@Composable
fun ArgDialog(s: Snippet, onDone: (Map<String, String>) -> Unit, onDismiss: () -> Unit) {
    val args = remember(s) { argsOf(s.text) }
    val vals = remember(s) { mutableStateMapOf<String, String>().apply { args.forEach { a -> put(a.name, a.default ?: "") } } }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().background(C.raised, RoundedCornerShape(18.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${s.keyword ?: ""} ${s.name}", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            args.forEach { a ->
                Text(a.name, color = C.muted, fontSize = 12.sp)
                if (a.options.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    a.options.forEach { o ->
                        val on = vals[a.name] == o
                        Text(o, color = if (on) C.onAccent else C.dim, fontSize = 13.sp, maxLines = 1,
                            modifier = Modifier.background(if (on) C.accent else C.panel2, RoundedCornerShape(10.dp))
                                .clickable { vals[a.name] = o }.padding(horizontal = 10.dp, vertical = 7.dp))
                    }
                }
                Field(vals[a.name] ?: "", a.default ?: "직접 입력", singleLine = true) { vals[a.name] = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("취소", color = C.muted, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp))
                Box(Modifier.weight(1f)) {
                    Text("넣기", color = C.onAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().background(C.accent, RoundedCornerShape(12.dp))
                            .clickable { onDone(vals.toMap()) }.padding(12.dp))
                }
            }
        }
    }
}

/** 칩 줄(최근) + «스니펫» 버튼 → 검색 목록. */
@Composable
fun SnippetBar(st: SnippetFieldState) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    if (SnippetStore.list.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("스니펫 ▾", color = C.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.background(C.panel2, RoundedCornerShape(10.dp)).clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 7.dp))
        SnippetStore.recent(ctx).forEach { s ->
            Text(s.keyword ?: s.name.take(10), color = C.dim, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.background(C.panel2, RoundedCornerShape(10.dp))
                    .border(1.dp, C.border, RoundedCornerShape(10.dp))
                    .clickable { st.insert(ctx, s) }.padding(horizontal = 10.dp, vertical = 7.dp))
        }
    }
    if (open) SnippetPicker(onPick = { open = false; st.insert(ctx, it) }, onDismiss = { open = false })
}

@Composable
fun SnippetPicker(onPick: (Snippet) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).background(C.raised, RoundedCornerShape(18.dp))
            .padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("스니펫 ${SnippetStore.list.size}개", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Field(q, "키워드·이름·내용 검색", singleLine = true) { q = it }
            val ql = q.trim().lowercase()
            val shown = if (ql.isEmpty()) SnippetStore.list else SnippetStore.list.filter {
                (it.keyword ?: "").lowercase().contains(ql) || it.name.lowercase().contains(ql) || it.text.lowercase().contains(ql)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown) { s ->
                    Column(Modifier.fillMaxWidth().background(C.raised, RoundedCornerShape(12.dp))
                        .clickable { onPick(s) }.padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (s.keyword != null) Text(s.keyword, color = C.accent, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                            Text(s.name, color = C.text, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(s.text.replace("\n", " ⏎ "), color = C.muted, fontSize = 12.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

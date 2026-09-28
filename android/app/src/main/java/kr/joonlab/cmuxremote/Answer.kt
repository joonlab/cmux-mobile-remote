package kr.joonlab.cmuxremote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import kr.joonlab.core.Lic

/** 보내기가 열려 있는가. allowlist 는 테스트 surface 전용이라 폰의 실제 탭에는 늘 403 이다 → «닫힘»으로 본다. */
fun sendOpen(mode: String?) = mode == "on"

fun sendClosedReason(mode: String?) = when (mode) {
    null -> "보내기 상태를 아직 모릅니다"
    "allowlist" -> "보내기는 테스트 탭에만 열려 있습니다(CMR_SEND=allowlist)"
    else -> "보내기가 닫혀 있습니다(CMR_SEND=$mode) — 맥에서 열어야 답할 수 있습니다"
}

private val sheetShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)

@Composable
private fun Sheet(key: String, summary: String, content: @Composable () -> Unit) {
    // 손잡이를 누르거나 아래로 끌면 한 줄로 접힌다 — 긴 질문 시트가 대화를 다 가리지 않게(사용자 요청 2026-09-25).
    // 같은 프롬프트(key=sig) 동안만 접힌 채 유지하고, 새 요청이 오면 다시 펼쳐서 보여 준다.
    var folded by androidx.compose.runtime.saveable.rememberSaveable(key) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .background(C.raised, sheetShape)
            .border(1.dp, C.blockedLine, sheetShape)
            .padding(start = 16.dp, end = 16.dp, bottom = if (folded) 4.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.fillMaxWidth()
            .clickable { folded = !folded }
            .pointerInput(key) {
                detectVerticalDragGestures { _, dy -> if (dy > 6) folded = true else if (dy < -6) folded = false }
            }
            .padding(top = 10.dp, bottom = if (folded) 8.dp else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(40.dp, 4.dp).background(C.border, RoundedCornerShape(2.dp)))
            if (folded) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Lic("lock-keyhole", C.amber, 15.dp)
                Text(summary, color = C.amberText, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("펼치기", color = C.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (!folded) content()
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text, textAlign = TextAlign.Center,
        color = if (enabled) C.onAccent else C.dim,
        fontSize = 15.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
            .background(if (enabled) C.blocked else C.panel2, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 15.dp),
    )
}

@Composable
fun Note(text: String, color: Color = C.muted) =
    Text(text, color = color, fontSize = 12.sp, lineHeight = 17.sp)

@Composable
private fun OptRow(n: Int?, label: String, desc: String?, selected: Boolean, multi: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .background(if (selected) C.amberTint else C.raised, shape)
            .border(1.dp, if (selected) C.blocked else C.border, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val mark = when {
            multi -> if (selected) "✔" else ""
            n != null -> "$n"
            else -> if (selected) "●" else ""
        }
        Text(mark, color = if (selected) C.onAccent else C.text, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
            modifier = Modifier.size(26.dp)
                .background(if (selected) C.blocked else C.panel2, RoundedCornerShape(if (multi) 6.dp else 8.dp))
                .padding(top = 4.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = C.text, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
            if (!desc.isNullOrBlank()) Text(desc, color = C.dim, fontSize = 12.5.sp, lineHeight = 18.sp)
        }
    }
}

/** 권한 요청 — 화면에서 읽은 선택지. 고른 뒤 «보내기»를 한 번 더 눌러야 간다(폰 오터치 방지). */
@Composable
fun PermissionSheet(p: Prompt, sendMode: String?, busy: Boolean, msg: String?, onAnswer: (Int) -> Unit) {
    var pick by remember(p.sig) { mutableStateOf<Int?>(null) }
    Sheet(p.sig, "권한 요청 · " + (p.question ?: "")) {
        Text(p.title ?: "권한 요청", color = C.blocked, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(p.question ?: "", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        if (!p.body.isNullOrBlank()) {
            Box(Modifier.fillMaxWidth().heightIn(max = 180.dp)
                .background(C.sunken, RoundedCornerShape(10.dp))
                .border(1.dp, C.border, RoundedCornerShape(10.dp))
                .verticalScroll(rememberScrollState()).padding(10.dp)) {
                Mono((if (p.truncatedTop) "…\n" else "") + p.body, color = C.textCode, size = 12)
            }
        }
        p.options.forEach { o ->
            OptRow(o.n, o.label, null, pick == o.n, multi = false) { pick = o.n }
        }
        val open = sendOpen(sendMode)
        PrimaryButton(
            when {
                busy -> "보내는 중…"
                pick == null -> "선택지를 고르세요"
                else -> "${pick}번 보내기"
            },
            enabled = open && pick != null && !busy,
        ) { pick?.let(onAnswer) }
        Note(if (!open) sendClosedReason(sendMode) else "보내기 직전에 화면을 다시 읽어, 같은 요청일 때만 누릅니다")
        if (msg != null) Note(msg, color = C.red)
    }
}

/** 질문 검토 화면(«Review your answers»). 자동 제출이 대조에 실패해 멈췄거나 맥에서 여기까지 온 경우의 출구. */
@Composable
fun ReviewSheet(p: Prompt, sendMode: String?, busy: Boolean, msg: String?, onAnswer: (Int) -> Unit) {
    var pick by remember(p.sig) { mutableStateOf<Int?>(p.options.firstOrNull { it.label.startsWith("Submit") }?.n) }
    Sheet(p.sig, "답 검토 — 제출 전 확인") {
        Text("답 검토 — 제출 전 마지막 확인", color = C.blocked, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            p.answers.forEach { (q, a) ->
                Column {
                    Text(q, color = C.muted, fontSize = 13.sp, lineHeight = 19.sp)
                    Text("→ $a", color = C.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (p.answers.isEmpty()) Note("답 목록을 화면에서 읽지 못했습니다 — «화면»으로 확인하세요")
        }
        p.options.forEach { o ->
            val label = when {
                o.label.startsWith("Submit") -> "이대로 제출"
                o.label.startsWith("Cancel") -> "취소(질문을 닫음)"
                else -> o.label
            }
            OptRow(o.n, label, null, pick == o.n, multi = false) { pick = o.n }
        }
        val open = sendOpen(sendMode)
        PrimaryButton(if (busy) "보내는 중…" else if (pick == null) "선택지를 고르세요" else "${pick}번 보내기",
            enabled = open && pick != null && !busy) { pick?.let(onAnswer) }
        Note(if (!open) sendClosedReason(sendMode) else "보내기 직전에 화면을 다시 읽어, 같은 검토 화면일 때만 누릅니다")
        if (msg != null) Note(msg, color = C.red)
    }
}

/** 질문(AskUserQuestion). 정본은 transcript 의 questions(설명·다중선택 포함), 없으면 화면에서 읽은 현재 질문 하나. */
data class QSpec(val question: String, val header: String?, val multi: Boolean, val labels: List<Pair<String, String>>)

fun specsFromTranscript(qs: JSONArray?): List<QSpec> {
    if (qs == null) return emptyList()
    return (0 until qs.length()).map { i ->
        val q = qs.getJSONObject(i)
        val opts = q.optJSONArray("options") ?: JSONArray()
        QSpec(q.optString("question"), q.optString("header").ifBlank { null }, q.optBoolean("multiSelect"),
            (0 until opts.length()).map { j -> opts.getJSONObject(j).let { it.optString("label") to it.optString("description") } })
    }
}

fun specFromScreen(p: Prompt): QSpec =
    QSpec(p.question ?: "", null, p.multi, p.options.filter { it.n != p.otherN }.map { it.label to it.desc })

@Composable
fun QuestionSheet(specs: List<QSpec>, fromScreen: Boolean, sendMode: String?, busy: Boolean, msg: String?,
                  screenQuestions: Int = 0, onAnswer: (List<QAns>) -> Unit) {
    // 화면엔 질문이 여럿인데 시트는 그중 하나만 안다(대화 기록이 아직 안 들어옴) → 보내면 뒷 질문이 빈다. 막는다
    val partial = screenQuestions > specs.size
    val picks = remember(specs) { mutableStateMapOf<Int, List<String>>() }
    val others = remember(specs) { mutableStateMapOf<Int, String>() }
    var page by remember(specs) { mutableStateOf(0) }
    val q = specs.getOrNull(page) ?: return
    Sheet(specs.joinToString("|") { it.question }, "질문 대기 · " + q.question) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (q.header != null) Chip(q.header, color = C.blocked, bg = C.amberTint)
            Text("질문 ${page + 1} / ${specs.size} · ${if (q.multi) "여러 개 고르기" else "하나 고르기"}",
                color = C.blocked, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(q.question, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 23.sp)
        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            q.labels.forEach { (label, desc) ->
                val cur = picks[page].orEmpty()
                OptRow(null, label, desc, label in cur, q.multi) {
                    picks[page] = if (q.multi) (if (label in cur) cur - label else cur + label)
                    else (if (label in cur) emptyList() else listOf(label))
                    if (!q.multi) others.remove(page)
                }
            }
        }
        Note("기타 — 직접 입력")
        Field(others[page].orEmpty(), "선택지 대신 내 말로 답하기(한 줄)", singleLine = true) {
            others[page] = it.replace("\n", " ")
            if (!q.multi && it.isNotBlank()) picks[page] = emptyList()
        }
        val answered = { i: Int -> picks[i].orEmpty().isNotEmpty() || !others[i].isNullOrBlank() }
        val last = page == specs.size - 1
        val open = sendOpen(sendMode)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (page > 0) Text("이전", color = C.text, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.width(90.dp).border(1.dp, C.border, RoundedCornerShape(14.dp))
                    .clickable { page-- }.padding(vertical = 15.dp))
            Box(Modifier.weight(1f)) {
                if (!last) PrimaryButton("다음", enabled = answered(page)) { page++ }
                else PrimaryButton(if (busy) "보내는 중…" else "이 답 보내기",
                    enabled = open && !busy && !partial && specs.indices.all(answered)) {
                    onAnswer(specs.mapIndexed { i, s ->
                        // 다중 선택은 화면 순서대로 늘어놓는다(검토 화면과 대조하기 쉽게)
                        val chosen = picks[i].orEmpty()
                        QAns(s.question, s.labels.map { it.first }.filter { it in chosen },
                            others[i]?.trim()?.ifBlank { null })
                    })
                }
            }
        }
        Note(when {
            !open -> sendClosedReason(sendMode)
            partial -> "질문이 ${screenQuestions}개인데 아직 하나만 읽었습니다 — 대화 기록을 불러오는 중입니다(잠시 뒤 «질문 1 / ${screenQuestions}»로 바뀝니다)"
            fromScreen -> "질문을 터미널 화면에서 읽었습니다 — 설명이 잘렸을 수 있습니다"
            else -> "질문과 선택지는 대화 기록의 AskUserQuestion 입력에서 읽었습니다"
        })
        if (msg != null) Note(msg, color = C.red)
    }
}

@Composable
fun Field(value: String, hint: String, singleLine: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth(),
          onChange: (String) -> Unit) {
    Box(modifier.heightIn(min = 46.dp)
        .background(C.raised, RoundedCornerShape(14.dp))
        .border(1.dp, C.border, RoundedCornerShape(14.dp))
        .padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (value.isEmpty()) Text(hint, color = C.dim, fontSize = 14.5.sp)
        BasicTextField(value, onChange, singleLine = singleLine, maxLines = if (singleLine) 1 else 6,
            textStyle = TextStyle(color = C.text, fontSize = 14.5.sp, lineHeight = 21.sp),
            cursorBrush = SolidColor(C.accent), modifier = Modifier.fillMaxWidth())
    }
}

val CLAUDE_KEYS = listOf("Esc" to "escape", "⏎" to "enter", "⇧Tab" to "shift+tab", "↑" to "up", "↓" to "down")
val SHELL_KEYS = listOf("^C" to "ctrl+c", "⏎" to "enter", "↑" to "up", "↓" to "down", "Tab" to "tab", "Esc" to "escape")

/** 입력창 + 키 줄 + 스니펫. 에이전트가 CMR_SEND=on 일 때만 누를 수 있다. */
@Composable
fun Composer(sendMode: String?, busy: Boolean, msg: String?, keys: List<Pair<String, String>> = CLAUDE_KEYS,
             hint: String = "메시지 — 작업 중이면 대기열에 들어갑니다", mono: Boolean = false,
             onSend: (String, (Boolean) -> Unit) -> Unit, onKey: (String) -> Unit) {
    val st = remember { SnippetFieldState() }
    val open = sendOpen(sendMode)
    Column(Modifier.fillMaxWidth().background(C.panel).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!open) {
            Note(sendClosedReason(sendMode))
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            keys.forEach { (lb, k) ->
                Text(lb, color = C.text, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(52.dp).background(C.panel2, RoundedCornerShape(10.dp))
                        .border(1.dp, C.border, RoundedCornerShape(10.dp))
                        .clickable(enabled = !busy) { onKey(k) }.padding(vertical = 9.dp))
            }
        }
        SnippetBar(st)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SnippetField(st, hint, modifier = Modifier.weight(1f), mono = mono)
            val text = st.value.text
            Text("↑", color = C.onAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.size(48.dp)
                    .background(if (text.isNotBlank() && !busy) C.accent else C.panel2, RoundedCornerShape(24.dp))
                    .clickable(enabled = text.isNotBlank() && !busy) { onSend(text) { ok -> if (ok) st.clear() } }
                    .padding(top = 10.dp))
        }
        if (msg != null) Note(msg, color = C.red)
    }
}

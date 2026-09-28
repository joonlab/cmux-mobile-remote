package kr.joonlab.cmuxremote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joonlab.core.CorePalette
import kr.joonlab.core.Lic
import kr.joonlab.core.Machine
import kr.joonlab.core.SF_SYMBOL
import kr.joonlab.core.ThemeMode

/**
 * 색 — 웹 대시보드(관제실) static/tokens.css 의 **Emerald Noir** 를 그대로 옮긴 것.
 * 규칙도 같다:
 *   [브랜드층] accent(에메랄드)·dim·border·gold — 도구가 자기를 드러내는 곳(링크·선택·버튼)
 *   [의미층]   green·lemon·amber·claude·red   — 상태. 카드 **배경 물듦**과 작은 아이콘으로만
 *   카드 왼쪽 3px 띠 = cmux 워크스페이스 색(사람이 칠한 것), 상태는 면(배경)으로.
 * 테마 상태(system → light → dark)는 core [ThemeMode]. core 컴포넌트에는 [CorePalette] 로 주입된다(CoreTheme(C)).
 */
object C : CorePalette {
    var mode get() = ThemeMode.mode; set(v) { ThemeMode.mode = v }
    val dark get() = ThemeMode.dark
    private fun t(light: Long, darkV: Long) = Color(if (dark) darkV else light)

    // 브랜드층
    override val accent get() = t(0xFF0F5132, 0xFF3CC492)
    val gold get() = t(0xFF7D6740, 0xFFC4A575)
    override val dim get() = t(0xFF555C5B, 0xFF98A49E)
    override val border get() = t(0xFFC3CCC7, 0xFF243128)
    // 의미층
    val green get() = t(0xFF1A7F3C, 0xFF5ED18A)
    val lemon get() = t(0xFF7A6A12, 0xFFDFC95F)
    val amber get() = t(0xFF8A6100, 0xFFD9AE45)
    val claude get() = t(0xFFA8501F, 0xFFD9855F)
    // 워크플로 진행 — green(작업중)·lemon(뒤에서)과 같은 램프에 두지 않는다. 저 둘은 «이 탭이
    // 한다»이고 이건 «에이전트 여럿이 딴 데서 한다»라 성격이 다르다(tokens.css --violet 과 같은 값).
    val violet get() = t(0xFF5B45C9, 0xFFA394F0)
    val red get() = t(0xFFC4322C, 0xFFEF6157)
    val amberText get() = t(0xFF6B4C00, 0xFFE6C97E)
    // 면
    override val bg get() = t(0xFFFBFCFB, 0xFF0A0F0D)
    override val panel get() = t(0xFFF2F5F3, 0xFF101714)
    override val panel2 get() = t(0xFFE7EBE8, 0xFF17201C)
    val sunken get() = t(0xFFEEF2EF, 0xFF070B09)
    override val raised get() = t(0xFFFFFFFF, 0xFF1B2521)
    // 글자
    override val text get() = t(0xFF16201B, 0xFFE8EEEA)
    val textCode get() = t(0xFF16201B, 0xFFC4D0CA)
    override val onAccent get() = t(0xFFFFFFFF, 0xFF03150E)
    // 선
    val edgeIdle get() = t(0xFFBFC8C3, 0xFF31403A)
    val groupFallback get() = t(0xFF8A948F, 0xFF3B4A43)
    val fadeIdle get() = if (dark) .66f else .87f

    // 합성 — rgb(var(--x-rgb) / a) 에 해당
    override val accentTint get() = accent.copy(alpha = .12f)
    val amberTint get() = amber.copy(alpha = .16f)
    val amberLine get() = amber.copy(alpha = .45f)
    val redTint get() = red.copy(alpha = .10f)

    // 옛 이름(1차 코드) — 역할로 이어 둔다
    val surface get() = panel
    val surface2 get() = panel2
    val line get() = border
    val muted get() = dim
    val blocked get() = amber
    val blockedBg get() = amberTint
    val blockedLine get() = amberLine
    val running get() = green
    val background get() = lemon
    val bad get() = red
    val ok get() = green
    val me get() = accentTint
}

fun statusColor(s: String?): Color = when (s) {
    "permission", "question" -> C.amber
    "running" -> C.green
    "workflow" -> C.violet
    "background" -> C.lemon
    "waiting" -> C.claude
    "unknown" -> C.red
    else -> C.dim
}

/** 카드 배경 물듦 — board.css .bcard.<상태> 와 같은 농도. */
fun statusTint(s: String?): Color = when (s) {
    "running" -> C.green.copy(alpha = .13f)
    "permission", "question" -> C.amber.copy(alpha = .16f)
    "workflow" -> C.violet.copy(alpha = .15f)
    "background" -> C.lemon.copy(alpha = .17f)
    "waiting" -> C.claude.copy(alpha = .09f)
    "unknown" -> C.red.copy(alpha = .12f)
    else -> Color.Transparent
}

val STATUS_HEAD = mapOf("running" to "작업 중", "permission" to "권한 대기", "question" to "질문 대기",
    "workflow" to "워크플로 진행",
    "background" to "뒤에서 진행", "waiting" to "입력 대기", "idle" to "유휴", "unknown" to "판정 불가")
private val STATUS_ICON = mapOf("running" to "loader-circle", "permission" to "lock-keyhole", "question" to "lock-keyhole",
    "workflow" to "workflow",
    "background" to "zap", "waiting" to "circle-dot", "idle" to "moon", "unknown" to "circle-question-mark")

@Composable
fun StatusIcon(s: String?, size: Dp = 16.dp) =
    Lic(STATUS_ICON[s] ?: "circle-question-mark", statusColor(s), size, spin = s == "running")

/** cmux 그룹 아이콘 — SF Symbol 이면 매핑, 이모지면 글자 그대로(icons.js icGroup 과 같다). */
@Composable
fun GroupIcon(symbol: String?, color: Color = C.dim, size: Dp = 14.dp) {
    when {
        symbol == null -> Lic("folder", color, size)
        SF_SYMBOL[symbol] != null -> Lic(SF_SYMBOL[symbol]!!, color, size)
        Regex("^[\\w.]+$").matches(symbol) -> Lic("folder", color, size)
        else -> Text(symbol, fontSize = (size.value - 1).sp)
    }
}

fun hex(c: String?, fallback: Color = C.groupFallback): Color =
    runCatching { Color(android.graphics.Color.parseColor(c)) }.getOrDefault(fallback)

fun ago(epochSec: Double?, nowMs: Long = System.currentTimeMillis()): String {
    if (epochSec == null) return "시각 모름"
    val s = (nowMs / 1000.0 - epochSec).toLong().coerceAtLeast(0)
    return when {
        s < 60 -> "${s}초"
        s < 3600 -> "${s / 60}분"
        s < 86400 -> "${s / 3600}시간"
        else -> "${s / 86400}일"
    }
}

/** 왼쪽 3px 띠(워크스페이스 색) + 1px 테두리 + 둥근 모서리 + 상태 물듦 — .bcard / .nav-card 공통. */
fun Modifier.card(wsc: Color, tint: Color, base: Color, radius: Dp = 10.dp, outline: Color? = null): Modifier {
    val shape = RoundedCornerShape(radius)
    return this.clip(shape).background(base).background(tint)
        .drawBehind { drawRect(wsc, size = Size(3.dp.toPx(), size.height)) }
        .border(1.dp, outline ?: C.border, shape)
}

@Composable
fun Dot(color: Color, hollow: Boolean = false) {
    val m = Modifier.size(8.dp).let {
        if (hollow) it.border(1.5.dp, color, CircleShape) else it.background(color, CircleShape)
    }
    Box(m)
}

@Composable
fun Chip(text: String, color: Color = C.dim, bg: Color = C.panel2) {
    Text(
        text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** 모노 태그 — «tab:1193» · 워크스페이스 제목(.btag / .nav-card .tag). on 이면 에메랄드. */
@Composable
fun Tag(text: String, on: Boolean = false) {
    Text(text, color = if (on) C.accent else C.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.background(if (on) C.accentTint else C.bg, RoundedCornerShape(5.dp))
            .border(1.dp, if (on) C.accent.copy(alpha = .35f) else C.border, RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp))
}

fun tabRef(ref: String?) = ref?.replace("surface:", "tab:")

/**
 * 상태별 목록의 카드 — 대시보드 «지금 어디» .nav-card 모양.
 * 왼쪽 상태 아이콘 · 제목 15/600 · 위치 줄(창 › 그룹 › 워크스페이스 · tab:N) · 따옴표 친 마지막 프롬프트 · 오른쪽 경과 시간.
 */
@Composable
fun TabCard(t: Tab, stale: Boolean, selected: Boolean, showMachine: Boolean = false, justDone: Boolean = false,
            onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val blocked = t.status == "permission" || t.status == "question"
    val fade = when {
        stale -> if (C.dark) .5f else .8f
        t.status == "idle" -> if (C.dark) .72f else .9f
        else -> 1f
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
            .card(hex(t.workspaceColor), if (stale) Color.Transparent else statusTint(t.status), C.panel,
                outline = if (selected) C.accent.copy(alpha = .6f) else null)
            .combinedClickable(onLongClick = onLongClick, onClick = onClick).heightIn(min = 48.dp).alpha(fade)
            .padding(start = 15.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.padding(top = 2.dp)) { StatusIcon(if (stale) "unknown" else t.status, 19.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(t.title, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (showMachine) Text(t.machine.label, color = C.accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                Text(t.windowLabel, color = C.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                listOfNotNull(t.groupName, t.workspaceTitle.ifBlank { null }).forEach {
                    Text("›", color = C.dim.copy(alpha = .45f), fontSize = 12.5.sp)
                    Text(it, color = C.dim, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                tabRef(t.surfaceRef)?.let { Tag(it) }
            }
            // 워크플로가 도는 탭 — 이름·단계·에이전트 수·경과. 탭 하나로는 안 보이는 일이라
            // 카드에서 한 줄로 드러낸다(관제실 .bk.wf 와 같은 정보).
            t.workflow?.let { w ->
                val el = w.startedAt?.let { (System.currentTimeMillis() / 1000.0 - it).toLong() }
                val dur = when {
                    el == null -> null
                    el >= 3600 -> "${el / 3600}시간 ${el % 3600 / 60}분"
                    el >= 60 -> "${el / 60}분"
                    else -> "${el}초"
                }
                val bits = listOfNotNull(w.phase,
                    if (w.agentsTotal > 0) "에이전트 ${w.agentsRunning}/${w.agentsTotal}" else null,
                    dur, if (w.runsLive > 1) "워크플로 ${w.runsLive}개" else null)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Lic("workflow", C.violet, 12.dp)
                    Text(listOfNotNull(w.name ?: w.runId, bits.joinToString(" · ").ifBlank { null })
                        .joinToString(" · "), color = C.violet, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // 백그라운드 서브에이전트 — 턴은 끝났는데 뒤에서 도는 일(관제실 .bk.bga 와 같은 정보)
            t.bgAgents.firstOrNull()?.let { a ->
                val el = a.startedAt?.let { (System.currentTimeMillis() / 1000.0 - it).toLong() }
                val dur = when {
                    el == null -> null
                    el >= 3600 -> "${el / 3600}시간 ${el % 3600 / 60}분"
                    el >= 60 -> "${el / 60}분"
                    else -> "${el}초"
                }
                val more = if (t.bgAgents.size > 1) "외 ${t.bgAgents.size - 1}개" else null
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Lic("bot", C.lemon, 12.dp)
                    Text(listOfNotNull(a.description ?: a.agentType ?: "에이전트", dur, more).joinToString(" · "),
                        color = C.lemon, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val line = when {
                t.askText != null -> t.askText
                t.status == "permission" -> t.promptLine ?: "권한 요청 — 눌러서 확인"
                t.lastPromptText != null -> "“${t.lastPromptText}”"
                else -> null
            }
            if (line != null) {
                val mono = t.status == "permission" && t.promptLine != null
                Text(line, color = if (blocked) C.amberText else C.dim, fontSize = 12.5.sp, lineHeight = 18.sp,
                    fontFamily = if (mono) FontFamily.Monospace else null, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = if (mono) Modifier.fillMaxWidth().background(C.sunken, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp) else Modifier)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(ago(t.lastActivity) + " 전", color = if (t.status == "running") C.green else C.dim, fontSize = 12.sp)
            if (justDone) Text("방금 끝남", color = C.claude, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.background(C.claude.copy(alpha = .12f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp))
        }
    }
}

/** 상태 묶음 머리 — «◌ 작업 중 2개» (.nav-grp-h). */
@Composable
fun StatusHead(s: String, n: Int, trailing: String? = null, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusIcon(s, 15.dp)
        Text(STATUS_HEAD[s] ?: s, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("${n}개", color = C.dim, fontSize = 12.sp)
        if (trailing != null) { Spacer(Modifier.weight(1f)); Text(trailing, color = C.accent, fontSize = 12.sp) }
    }
}

@Composable
fun MachineHeader(m: Machine, st: MachineState?, collapsedIdle: Int, onToggleIdle: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Lic(if (m.key == "home") "house" else "app-window", C.text, 15.dp)
            Text(m.label, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            when {
                st == null -> { Dot(C.dim, hollow = true); Text("확인 중…", color = C.dim, fontSize = 12.5.sp) }
                st.connected -> { Dot(C.green); Text("소켓 제어 가능 · claude ${st.tabs.size}", color = C.dim, fontSize = 12.5.sp) }
                else -> { Dot(C.red, hollow = true); Text("연결 안 됨", color = C.red, fontSize = 12.5.sp) }
            }
            Spacer(Modifier.weight(1f))
            if (collapsedIdle > 0) {
                Text("유휴 $collapsedIdle 보기", color = C.accent, fontSize = 12.sp,
                    modifier = Modifier.clickable(onClick = onToggleIdle).padding(8.dp))
            }
        }
        if (st?.error != null) {
            val last = st.okAt?.let { "마지막 성공 ${ago(it / 1000.0)} 전" } ?: "한 번도 연결된 적 없음"
            val hint = if (m.key == "laptop") "덮였거나 Tailscale 이 로그아웃됐을 수 있습니다." else
                "노트북·폰의 Tailscale 이 먼저 의심됩니다."
            Text("$last · $hint\n${st.error}", color = C.red, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 6.dp).fillMaxWidth()
                    .background(C.redTint, RoundedCornerShape(10.dp))
                    .border(1.dp, C.red.copy(alpha = .35f), RoundedCornerShape(10.dp)).padding(10.dp))
        }
        st?.warnings?.forEach {
            Text("⚠ $it", color = C.amberText, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
fun Mono(text: String, color: Color = C.textCode, size: Int = 12) {
    Text(text, color = color, fontSize = size.sp, fontFamily = FontFamily.Monospace, lineHeight = (size + 5).sp)
}

@Composable
fun Hspace(w: Int) = Spacer(Modifier.width(w.dp))

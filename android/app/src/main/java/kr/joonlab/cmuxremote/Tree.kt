package kr.joonlab.cmuxremote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.joonlab.core.Machine
import kr.joonlab.core.Lic
import kr.joonlab.core.ScaledText
import kr.joonlab.core.pinchTextScale
import kr.joonlab.core.PinchBadge
import kr.joonlab.core.TextSizeButton

fun shortPath(p: String?): String = (p ?: "").replace(Regex("^/Users/[^/]+"), "~")

/** 창별 보기에서 누른 것. */
sealed class TreeAction {
    // force = 맥 화면 따라가기 모드여도 폰에서 연다(길게 누르기)
    data class OpenClaude(val m: Machine, val surfaceId: String, val force: Boolean = false) : TreeAction()
    data class OpenShell(val m: Machine, val surfaceId: String, val title: String, val force: Boolean = false) : TreeAction()
    data class NewWs(val m: Machine, val windowId: String?, val groupId: String?) : TreeAction()
    data class WsMenu(val m: Machine, val ws: Ws) : TreeAction()
}

/**
 * 창 → 그룹 → 워크스페이스 → 탭. 대시보드(관제실) 창별 컬럼 보드(board.css)를 폰 세로 목록으로 옮긴 것.
 *   .bwin  창 = 테두리 두른 패널, 머리에 상태별 개수
 *   .bgroup 그룹 = 왼쪽 3px 그룹 색 + 아이콘·이름
 *   .bws   워크스페이스에 탭이 여럿이면 점선 상자로 묶고, 하나면 카드만
 *   .bcard 카드 = 왼쪽 3px 워크스페이스 색 + 상태 물듦 + tab:N 태그
 * 순서는 cmux 배치 그대로. 그룹 앵커(그룹 머리 워크스페이스)는 claude 탭이 없으면 그리지 않는다.
 */
fun LazyListScope.windowBoard(m: Machine, tree: TreeState?, st: MachineState?, sel: Sel?, act: (TreeAction) -> Unit) {
    item(key = "th-${m.key}") { MachineHeader(m, st, 0) {} }
    if (tree == null) {
        item { Text("창 목록을 불러오는 중…", color = C.dim, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
        return
    }
    tree.windows.forEach { w -> item(key = "w-${m.key}-${w.id}") { WinPanel(m, w, sel, act) } }
}

private val HEAD_ORDER = listOf("permission", "question", "running", "background", "waiting", "idle")

@Composable
internal fun WinPanel(m: Machine, w: Win, sel: Sel?, act: (TreeAction) -> Unit) {
    val claudeTabs = w.workspaces.flatMap { it.tabs }.filter { it.isClaude }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
        .background(C.panel, RoundedCornerShape(11.dp))
        .border(1.dp, if (w.active) C.accent.copy(alpha = .5f) else C.border, RoundedCornerShape(11.dp))
        .padding(start = 9.dp, end = 9.dp, top = 9.dp, bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Lic("app-window", C.text, 15.dp)
            Text(w.label, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (w.active) Text("활성", color = C.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.background(C.accentTint, RoundedCornerShape(5.dp))
                    .border(1.dp, C.accent.copy(alpha = .4f), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
            HEAD_ORDER.forEach { s ->
                val n = claudeTabs.count { it.status == s }
                if (n > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    StatusIcon(s, 12.dp); Text("$n", color = C.dim, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Text("${claudeTabs.size}", color = C.dim, fontSize = 12.sp)
            Row(Modifier.clickable { act(TreeAction.NewWs(m, w.id, null)) }.padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Lic("x", C.accent, 13.dp, modifier = Modifier.rotate(45f)); Text("새로", color = C.accent, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
            }
        }
        Box(Modifier.fillMaxWidth().size(1.dp).background(C.border))
        Spacer(Modifier.size(8.dp))
        // 이어진 같은 그룹을 한 묶음으로
        val runs = mutableListOf<Pair<Grp?, MutableList<Ws>>>()
        w.workspaces.forEach { ws ->
            val g = w.groups.firstOrNull { it.id == ws.groupId }
            if (runs.isNotEmpty() && runs.last().first?.id == g?.id) runs.last().second.add(ws) else runs.add(g to mutableListOf(ws))
        }
        runs.forEach { (g, list) ->
            val shown = list.filter { !(it.isAnchor && it.tabs.none { t -> t.isClaude }) }
            if (g == null) {
                Column(Modifier.padding(bottom = 9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    shown.forEach { WsBlock(m, it, sel, act) }
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(bottom = 9.dp)
                    .drawBehind { drawRect(hex(g.color), size = Size(3.dp.toPx(), size.height)) }
                    .padding(start = 10.dp)) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("▾", color = C.dim, fontSize = 10.sp)
                        GroupIcon(g.icon, C.dim, 13.dp)
                        Text(g.name, color = C.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (g.pinned) Lic("pin", C.gold, 12.dp)
                        Spacer(Modifier.weight(1f))
                        Text("${shown.sumOf { ws -> ws.tabs.count { it.isClaude } }}", color = C.dim, fontSize = 12.sp)
                        Lic("x", C.accent, 15.dp, modifier = Modifier.rotate(45f)
                            .clickable { act(TreeAction.NewWs(m, w.id, g.id)) }.padding(2.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { shown.forEach { WsBlock(m, it, sel, act) } }
                }
            }
        }
    }
}

/** 워크스페이스 — 탭이 여럿이면 점선 상자(.bws), 하나면 카드만. */
@Composable
private fun WsBlock(m: Machine, ws: Ws, sel: Sel?, act: (TreeAction) -> Unit) {
    if (ws.tabs.size <= 1) {
        ws.tabs.firstOrNull()?.let { TreeCard(m, ws, it, sel, single = true, act) }
            ?: WsHeadOnly(m, ws, act)
        return
    }
    Column(Modifier.fillMaxWidth()
        .drawBehind {
            drawRoundRect(C.border, cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
        }.padding(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 3.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Lic("layers", C.dim, 12.dp)
            Text(ws.title, color = C.text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${ws.tabs.size}", color = C.dim, fontSize = 11.5.sp)
            Text("⋯", color = C.dim, fontSize = 16.sp,
                modifier = Modifier.clickable { act(TreeAction.WsMenu(m, ws)) }.padding(horizontal = 8.dp))
        }
        ws.tabs.forEach { TreeCard(m, ws, it, sel, single = false, act) }
    }
}

@Composable
private fun WsHeadOnly(m: Machine, ws: Ws, act: (TreeAction) -> Unit) {
    Row(Modifier.fillMaxWidth().card(hex(ws.color), Color.Transparent, C.panel2, 8.dp)
        .clickable { act(TreeAction.WsMenu(m, ws)) }.padding(start = 11.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(ws.title + " (탭 없음)", color = C.dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text("⋯", color = C.dim, fontSize = 16.sp)
    }
}

/** 카드 한 장(.bcard). claude 탭이 아니면(셸·브라우저) 흐리게. */
@Composable
private fun TreeCard(m: Machine, ws: Ws, t: TreeTab, sel: Sel?, single: Boolean, act: (TreeAction) -> Unit) {
    val picked = sel?.surfaceId == t.surfaceId && sel.machineKey == m.key
    val fade = when {
        !t.isClaude -> if (C.dark) .72f else .9f
        t.status == "idle" -> C.fadeIdle
        else -> 1f
    }
    Column(Modifier.fillMaxWidth()
        .card(hex(ws.color), statusTint(t.status), C.panel2, 8.dp, outline = if (picked) C.accent.copy(alpha = .6f) else null)
        .combinedClickable(enabled = t.type == "terminal",
            onLongClick = { act(if (t.isClaude) TreeAction.OpenClaude(m, t.surfaceId, true) else TreeAction.OpenShell(m, t.surfaceId, t.title, true)) }) {
            act(if (t.isClaude) TreeAction.OpenClaude(m, t.surfaceId) else TreeAction.OpenShell(m, t.surfaceId, t.title))
        }
        .padding(start = 11.dp, end = 6.dp, top = 7.dp, bottom = 7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.alpha(fade), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            when {
                t.isClaude -> StatusIcon(t.status, 15.dp)
                t.type == "browser" -> Lic("globe", C.dim, 14.dp)
                else -> Lic("square-terminal", C.dim, 14.dp)
            }
            Text(t.claudeTitle ?: t.title, color = C.text, fontSize = 14.sp,
                fontWeight = if (t.isClaude) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (t.isClaude) Text(ago(t.lastActivity), color = if (t.status == "running") C.green else C.dim, fontSize = 11.5.sp)
            if (single) Text("⋯", color = C.dim, fontSize = 16.sp,
                modifier = Modifier.clickable { act(TreeAction.WsMenu(m, ws)) }.padding(horizontal = 8.dp))
        }
        Row(Modifier.alpha(fade).padding(start = 22.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            tabRef(t.ref)?.let { Tag(it) }
            if (ws.selected && t.isClaude) Tag("보임", on = true)
            // 워크스페이스 제목 태그는 남는 폭만 쓴다(넘치면 말줄임) — 고정 폭으로 두면 줄 밖으로 잘린다(실측)
            if (single && ws.title.isNotBlank() && ws.title != (t.claudeTitle ?: t.title))
                Box(Modifier.weight(1f, fill = false)) { Tag(ws.title) }
        }
    }
}

/** 셸 탭 — 화면 원문이 곧 화면이다. 명령은 줄마다 Enter. */
@Composable
fun TermScreen(m: Machine, surfaceId: String, title: String, sendMode: String?, active: Boolean, showBack: Boolean,
               claudeNow: Boolean, onOpenChat: () -> Unit, onBack: () -> Unit) {
    var text by remember(surfaceId) { mutableStateOf("") }
    var err by remember(surfaceId) { mutableStateOf<String?>(null) }
    var busy by remember(surfaceId) { mutableStateOf(false) }
    var msg by remember(surfaceId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    LaunchedEffect(surfaceId, active) {
        while (active) {
            withContext(Dispatchers.IO) { runCatching { Api.screen(m, surfaceId, 200) } }
                .onSuccess { text = it.trimEnd(); err = null }.onFailure { err = it.message }
            delay(1500)
        }
    }
    fun act(block: () -> org.json.JSONObject, done: (Boolean) -> Unit = {}) {
        busy = true; msg = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(block) }
            r.onFailure { msg = it.message }
            delay(300)
            withContext(Dispatchers.IO) { runCatching { Api.screen(m, surfaceId, 200) } }.onSuccess { text = it.trimEnd() }
            busy = false; done(r.isSuccess)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (showBack) Text("‹", color = C.text, fontSize = 30.sp,
                modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 14.dp, vertical = 2.dp))
            else Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text("셸 · ${m.label}", color = C.muted, fontSize = 12.sp)
            }
            TextSizeButton()
            Spacer(Modifier.width(8.dp))
            if (claudeNow) Text("대화로 보기", color = C.text, fontSize = 13.sp,
                modifier = Modifier.border(1.dp, C.border, RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenChat).padding(horizontal = 12.dp, vertical = 8.dp))
        }
        Box(Modifier.fillMaxWidth().size(1.dp).background(C.border))
        val tsCtx = androidx.compose.ui.platform.LocalContext.current
        Box(Modifier.weight(1f).fillMaxWidth().pinchTextScale(tsCtx)) {
            ScaledText {
                Column(Modifier.fillMaxSize()) {
                if (err != null) Text("화면을 못 읽었습니다: $err", color = C.bad, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
                SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(12.dp)) {
                    Mono(text.ifEmpty { "읽는 중…" }, size = 11)
                }
                Composer(sendMode, busy, msg, keys = SHELL_KEYS, hint = "명령 — 줄마다 Enter", mono = true,
                    onSend = { t, done -> act({ Api.send(m, surfaceId, t) }, done) },
                    onKey = { k -> act({ Api.key(m, surfaceId, k) }) })

                }
            }
            PinchBadge(Modifier.align(Alignment.Center))
        }
    }
}

@Composable
fun DialogBox(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 700.dp).background(C.raised, RoundedCornerShape(18.dp))
            .verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
fun Pick(label: String, on: Boolean, onClick: () -> Unit) {
    Text(label, color = if (on) C.onAccent else C.dim, fontSize = 13.sp, maxLines = 1,
        modifier = Modifier.background(if (on) C.accent else C.panel2, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp))
}

@Composable
fun Buttons(okLabel: String, okColor: Color = C.accent, enabled: Boolean, busy: Boolean, onCancel: () -> Unit, onOk: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("취소", color = C.muted, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onCancel).padding(12.dp))
        Box(Modifier.weight(1f)) {
            Text(if (busy) "보내는 중…" else okLabel, color = if (enabled && !busy) C.onAccent else C.dim,
                fontSize = 14.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().background(if (enabled && !busy) okColor else C.panel2, RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled && !busy, onClick = onOk).padding(13.dp))
        }
    }
}

/** 새 워크스페이스 — 이름 · 폴더 · 시작 명령(스니펫 확장, 예: ;cc) · 창 · 그룹. */
@Composable
fun NewWsDialog(tree: TreeState, windowId: String?, groupId: String?, busy: Boolean, msg: String?,
                onDismiss: () -> Unit, onCreate: (String, String?, String?, String?, String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var cwd by remember { mutableStateOf("~") }
    var win by remember { mutableStateOf(windowId ?: tree.windows.firstOrNull { it.active }?.id ?: tree.windows.firstOrNull()?.id) }
    var grp by remember { mutableStateOf(groupId) }
    val cmd = remember { SnippetFieldState() }
    val cwds = remember(tree) {
        tree.windows.flatMap { w -> w.workspaces.mapNotNull { it.cwd } }.filter { shortPath(it) != "~" }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(8)
    }
    DialogBox("새 워크스페이스", onDismiss) {
        Note("이름")
        Field(name, "예: 회의 메모 정리", singleLine = true) { name = it }
        Note("폴더(cwd)")
        Field(cwd, "~ 또는 절대경로", singleLine = true) { cwd = it }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf("~") + cwds.map { shortPath(it) }).forEach { c -> Pick(c.takeLast(40), cwd == c) { cwd = c } }
        }
        Note("시작 명령 — 비우면 빈 셸. 스니펫 그대로 됩니다(;cc → claude --dangerously-skip-permissions)")
        SnippetBar(cmd)
        SnippetField(cmd, "예: claude", singleLine = true, mono = true)
        Note("창")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tree.windows.forEach { w -> Pick(w.label + if (w.active) " ·활성" else "", win == w.id) { win = w.id; grp = null } }
        }
        val groups = tree.windows.firstOrNull { it.id == win }?.groups.orEmpty()
        if (groups.isNotEmpty()) {
            Note("그룹")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pick("없음", grp == null) { grp = null }
                groups.forEach { g -> Pick(g.name, grp == g.id) { grp = g.id } }
            }
        }
        if (msg != null) Note(msg, color = C.red)
        Buttons("만들기", enabled = name.isNotBlank() && sendOpen(tree.sendMode), busy = busy, onCancel = onDismiss) {
            val c = cwd.trim().let { if (it == "~" || it.isEmpty()) null else it }   // «~» 는 에이전트가 그 맥의 홈으로 푼다(expanduser)
            onCreate(name.trim(), c, cmd.value.text.trim().ifEmpty { null }, win, grp)
        }
        if (!sendOpen(tree.sendMode)) Note(sendClosedReason(tree.sendMode))
    }
}

@Composable
fun WsMenuDialog(ws: Ws, sendMode: String?, busy: Boolean, msg: String?, onDismiss: () -> Unit,
                 onRename: (String) -> Unit, onClose: (Int) -> Unit) {
    var title by remember(ws.id) { mutableStateOf(ws.title) }
    var confirmClose by remember(ws.id) { mutableStateOf(false) }
    val claude = ws.tabs.count { it.isClaude }
    DialogBox(ws.title, onDismiss) {
        if (ws.cwd != null) Note(shortPath(ws.cwd))
        Note("이름 바꾸기")
        Field(title, "새 이름", singleLine = true) { title = it }
        Buttons("이름 바꾸기", enabled = sendOpen(sendMode) && title.isNotBlank() && title != ws.title, busy = busy,
            onCancel = onDismiss) { onRename(title.trim()) }
        Box(Modifier.fillMaxWidth().size(1.dp).background(C.panel2))
        if (ws.isAnchor) Note("그룹 머리(앵커) 워크스페이스라 닫을 수 없습니다")
        else if (!confirmClose) Text("워크스페이스 닫기…", color = C.bad, fontSize = 14.sp,
            modifier = Modifier.clickable(enabled = sendOpen(sendMode)) { confirmClose = true }.padding(vertical = 8.dp))
        else {
            Text(if (claude > 0) "claude 탭 ${claude}개가 함께 종료됩니다. 되돌릴 수 없습니다." else "탭 ${ws.tabs.size}개가 닫힙니다. 되돌릴 수 없습니다.",
                color = C.bad, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            ws.tabs.forEach { t -> Note("· " + (t.claudeTitle ?: t.title) + if (t.isClaude) " (claude ${t.statusLabel})" else "") }
            Buttons("정말 닫기", okColor = C.bad, enabled = true, busy = busy, onCancel = { confirmClose = false }) { onClose(claude) }
        }
        if (msg != null) Note(msg, color = C.red)
        if (!sendOpen(sendMode)) Note(sendClosedReason(sendMode))
    }
}

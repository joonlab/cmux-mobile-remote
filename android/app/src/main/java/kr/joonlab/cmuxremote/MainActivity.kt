package kr.joonlab.cmuxremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.joonlab.core.CoreTheme
import kr.joonlab.core.Lic
import kr.joonlab.core.MACHINES
import kr.joonlab.core.Machine
import kr.joonlab.core.PinchBadge
import kr.joonlab.core.ScaledText
import kr.joonlab.core.SplitPane
import kr.joonlab.core.TextSizeButton
import kr.joonlab.core.WIDE
import kr.joonlab.core.pinchTextScale
import kr.joonlab.core.rememberPaneState

class MainActivity : ComponentActivity() {
    /** 화면이 안 보이는 동안은 폴링을 쉰다(배터리). */
    private val resumed = mutableStateOf(false)
    /** 다른 앱(기록 앱)이 연 링크 — 처리하면 비운다. singleTask 라 떠 있는 동안 온 링크는 onNewIntent 로 온다. */
    private val link = mutableStateOf<Link?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) link.value = parseLink(intent?.data)
        setContent { App(resumed.value, link.value) { link.value = null } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        parseLink(intent.data)?.let { link.value = it }
    }

    override fun onResume() { super.onResume(); resumed.value = true }
    override fun onPause() { resumed.value = false; super.onPause() }
}

/** autoChat — 이어가기로 방금 띄운 셸 탭: claude 가 올라와 목록에 잡히면 대화 화면으로 저절로 넘어간다. */
data class Sel(val machineKey: String, val surfaceId: String, val kind: String = "claude", val title: String = "",
               val autoChat: Boolean = false)

@Composable
fun App(active: Boolean, link: Link? = null, onLinkDone: () -> Unit = {}) {
    val states = remember { mutableStateMapOf<String, MachineState>() }
    val trees = remember { mutableStateMapOf<String, TreeState>() }
    var sel by remember { mutableStateOf<Sel?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(prefs.getString("mode", "window") ?: "window") }
    val listPane = rememberPaneState("list", 360f)                              // 펼친 화면 목록 폭(dp)·접힘
    // 목록 전체 화면 — 펼친 화면에서 대화 칸을 숨기고 목록(창별이면 창 보드)만. 맥 화면 따라가기와 짝(상황판)
    var listFull by remember { mutableStateOf(prefs.getBoolean("listFull", false)) }
    // 맥 화면 따라가기 — 켜면 탭을 누를 때 폰이 아니라 **맥의 cmux** 가 그 탭으로 넘어간다(데스크탑 작업 중 상황판 용도).
    // 길게 누르면 이 모드에서도 폰에서 연다. 끄면 예전 그대로.
    var deskMode by remember { mutableStateOf(prefs.getBoolean("deskMode", false)) }
    var toast by remember { mutableStateOf<Pair<String, Boolean>?>(null) }      // (문구, 오류?)
    var focused by remember { mutableStateOf<Sel?>(null) }                        // 맥에서 방금 연 탭(목록 강조)
    var dialog by remember { mutableStateOf<TreeAction?>(null) }
    var dBusy by remember { mutableStateOf(false) }
    var dMsg by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // 스니펫은 한 번만 — 먼저 답하는 맥에서(두 맥에 같은 사본이 있다)
    LaunchedEffect(Unit) {
        while (SnippetStore.list.isEmpty()) {
            for (m in MACHINES) {
                val r = withContext(Dispatchers.IO) { runCatching { Api.snippets(m) } }
                if (r.isSuccess && r.getOrNull()!!.isNotEmpty()) { SnippetStore.load(r.getOrNull()!!); break }
            }
            if (SnippetStore.list.isEmpty()) delay(15000)
        }
    }
    // 창별 보기일 때만 트리를 받는다(맥에서 cmux tree 를 부르는 값이 있다)
    MACHINES.forEach { m ->
        LaunchedEffect(m.key, active, mode) {
            while (active && mode == "window") {
                withContext(Dispatchers.IO) { runCatching { Api.tree(m) } }.onSuccess { trees[m.key] = it }
                delay(5000)
            }
        }
    }
    fun refreshTree(m: Machine) = scope.launch {
        withContext(Dispatchers.IO) { runCatching { Api.tree(m) } }.onSuccess { trees[m.key] = it }
    }
    fun run(m: Machine, block: () -> org.json.JSONObject, ok: (org.json.JSONObject) -> Unit) {
        dBusy = true; dMsg = null
        scope.launch {
            withContext(Dispatchers.IO) { runCatching(block) }
                .onSuccess { ok(it); dialog = null; refreshTree(m) }.onFailure { dMsg = it.message }
            dBusy = false
        }
    }
    fun focusOnMac(m: Machine, surfaceId: String, label: String) {
        focused = Sel(m.key, surfaceId)
        toast = "${m.label} cmux 로 넘기는 중… $label" to false
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { Api.focus(m, surfaceId) } }
                .onSuccess { r ->
                    toast = (if (r.optBoolean("verified", true)) "${m.label} cmux → ${r.optString("where")}"
                             else "${m.label} cmux 로 보냈지만 활성 탭 확인이 안 됐습니다 — ${r.optString("where")}") to false
                }
                .onFailure { e -> toast = "맥으로 못 넘겼습니다: ${e.message}" to true }
            delay(3500); toast = null
        }
    }
    val onAction: (TreeAction) -> Unit = { a ->
        when (a) {
            is TreeAction.OpenClaude ->
                if (deskMode && !a.force) focusOnMac(a.m, a.surfaceId, "") else sel = Sel(a.m.key, a.surfaceId)
            is TreeAction.OpenShell ->
                if (deskMode && !a.force) focusOnMac(a.m, a.surfaceId, a.title) else sel = Sel(a.m.key, a.surfaceId, "shell", a.title)
            else -> { dMsg = null; dialog = a }
        }
    }
    when (val d = dialog) {
        is TreeAction.NewWs -> trees[d.m.key]?.let { t ->
            NewWsDialog(t, d.windowId, d.groupId, dBusy, dMsg, onDismiss = { dialog = null }) { name, cwd, cmd, win, grp ->
                run(d.m, { Api.newWorkspace(d.m, name, cwd, cmd, win, grp) }) { r ->
                    val sf = r.optJSONArray("surfaces")?.optString(0)
                    if (!sf.isNullOrBlank()) sel = Sel(d.m.key, sf, "shell", name)
                }
            }
        }
        is TreeAction.WsMenu -> WsMenuDialog(d.ws, trees[d.m.key]?.sendMode, dBusy, dMsg, onDismiss = { dialog = null },
            onRename = { t -> run(d.m, { Api.renameWorkspace(d.m, d.ws.id, t) }) {} },
            onClose = { n -> run(d.m, { Api.closeWorkspace(d.m, d.ws.id, d.ws.title, n) }) {
                if (d.ws.tabs.any { it.surfaceId == sel?.surfaceId }) sel = null } })
        else -> {}
    }

    // 두 맥을 **따로** 폴링한다 — 한쪽이 타임아웃이어도 다른 쪽 갱신이 밀리지 않게.
    MACHINES.forEach { m ->
        LaunchedEffect(m.key, active) {
            while (active) {
                val r = withContext(Dispatchers.IO) { runCatching { Api.sessions(m) } }
                val prev = states[m.key]
                states[m.key] = r.fold(
                    onSuccess = { it },
                    // 실패해도 직전 목록은 남긴다 — 화면에서 흐리게 «지금 상태 모름»으로.
                    onFailure = { e -> (prev ?: MachineState()).copy(error = e.message ?: e.toString()) },
                )
                delay(5000)
            }
        }
    }

    // 기록 앱에서 온 링크 — 탭 열기는 바로, 이어가기는 맥 고르는 시트로(기본값 없음)
    var resumeLink by remember { mutableStateOf<Link.Resume?>(null) }
    LaunchedEffect(link) {
        when (link) {
            is Link.OpenTab -> { sel = Sel(link.machineKey, link.surfaceId); onLinkDone() }
            is Link.Resume -> { resumeLink = link; onLinkDone() }
            null -> {}
        }
    }
    resumeLink?.let { rl -> ResumeDialog(rl, states, onDismiss = { resumeLink = null }) { s -> resumeLink = null; sel = s } }

    // 폰에서 탭을 열면(따라가기 꺼짐·길게 누르기) 전체 화면을 풀고 대화를 보여 준다
    LaunchedEffect(sel) { if (sel != null && listFull) { listFull = false; prefs.edit().putBoolean("listFull", false).apply() } }
    fun toggleFull() { listFull = !listFull; prefs.edit().putBoolean("listFull", listFull).apply() }
    val selTab = sel?.let { s -> states[s.machineKey]?.tabs?.firstOrNull { it.surfaceId == s.surfaceId } }
    LaunchedEffect(selTab != null, sel) { sel?.let { s -> if (s.autoChat && s.kind == "shell" && selTab != null) sel = s.copy(kind = "claude", autoChat = false) } }
    val list: @Composable (Boolean) -> Unit = { wideNow ->
        SessionList(states, if (deskMode) focused else sel, mode, trees,
            onMode = { mode = it; prefs.edit().putString("mode", it).apply() },
            deskMode = deskMode, onDeskMode = { deskMode = it; prefs.edit().putBoolean("deskMode", it).apply() },
            onAction = onAction,
            onLongPick = { sel = it }, full = if (wideNow) listFull else null, onFull = { toggleFull() }) { pick ->
            if (deskMode) {
                val m = MACHINES.first { it.key == pick.machineKey }
                val t = states[pick.machineKey]?.tabs?.firstOrNull { it.surfaceId == pick.surfaceId }
                focusOnMac(m, pick.surfaceId, t?.title ?: "")
            } else sel = pick
        }
    }
    val detail: @Composable (Boolean) -> Unit = { back ->
        val s = sel!!
        val m = MACHINES.first { it.key == s.machineKey }
        if (s.kind == "shell") TermScreen(m, s.surfaceId, s.title, states[s.machineKey]?.sendMode, active, back,
            claudeNow = selTab != null, onOpenChat = { sel = s.copy(kind = "claude") }) { sel = null }
        else ChatScreen(m, s.surfaceId, selTab, states[s.machineKey]?.sendMode, active, showBack = back) { sel = null }
    }
    val selMachine = sel?.let { s -> MACHINES.first { it.key == s.machineKey } }
    val selSend = sel?.let { s -> states[s.machineKey]?.sendMode }

    CoreTheme(C) {
    BoxWithConstraints(Modifier.fillMaxSize().background(C.bg).safeDrawingPadding()) {
        val wide = maxWidth >= WIDE            // 폴드를 펼치면 목록 | 대화 두 칸
        if (wide && listFull) {
            if (mode == "window") BoardScreen(states, trees, if (deskMode) focused else sel, mode,
                onMode = { mode = it; prefs.edit().putString("mode", it).apply() },
                deskMode = deskMode, onDeskMode = { deskMode = it; prefs.edit().putBoolean("deskMode", it).apply() },
                onExitFull = { toggleFull() }, onAction = onAction, onLongPick = { sel = it }) { pick ->
                if (deskMode) {
                    val m = MACHINES.first { it.key == pick.machineKey }
                    val t = states[pick.machineKey]?.tabs?.firstOrNull { it.surfaceId == pick.surfaceId }
                    focusOnMac(m, pick.surfaceId, t?.title ?: "")
                } else sel = pick
            }
            else list(true)
        } else if (wide) {
            // 목록 | 레일 | 대화 — 레일(core): 탭 = 접기/펼치기, 끌기 = 폭(최소 260dp, 대화 쪽 최소 320dp, 200dp 밑이면 접힘)
            SplitPane(listPane, maxWidth, pane = { list(true) },
                railExtra = {
                    // 접혀 있을 때 — 막힌 탭 수
                    val n = MACHINES.sumOf { m -> states[m.key]?.let { st -> st.tabs.count { it.status in st.blocked } } ?: 0 }
                    Spacer(Modifier.size(14.dp))
                    if (n > 0) { Lic("lock-keyhole", C.amber, 15.dp); Text("$n", color = C.amber, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold) }
                }) {
                if (sel != null && selMachine != null) {
                    detail(false)
                } else {
                    Text(if (listPane.folded) "› 를 눌러 목록을 펼치고 탭을 고르세요" else "왼쪽에서 탭을 고르세요", color = C.muted, modifier = Modifier.align(Alignment.Center))
                }
            }
        } else if (sel != null && selMachine != null) {
            BackHandler { sel = null }
            detail(true)
        } else {
            list(false)
        }
        toast?.let { (msg, bad) ->
            Text(msg, color = C.onAccent, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp, start = 20.dp, end = 20.dp)
                    .background(if (bad) C.red else C.accent, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 11.dp))
        }
    }
    }
}

@Composable
fun SessionList(states: Map<String, MachineState>, sel: Sel?, mode: String, trees: Map<String, TreeState>,
                onMode: (String) -> Unit, deskMode: Boolean = false, onDeskMode: (Boolean) -> Unit = {},
                onAction: (TreeAction) -> Unit, onLongPick: (Sel) -> Unit = {},
                full: Boolean? = null, onFull: () -> Unit = {}, onPick: (Sel) -> Unit) {
    val showIdle = remember { mutableStateMapOf<String, Boolean>() }
    val listState = rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // 맥별 섹션 안의 줄 위치 — «나를 기다림» 요약 줄에서 그 맥으로 바로 내려가려고 센다.
    val headerIndex = mutableMapOf<String, Int>()
    var idx = 2
    MACHINES.forEach { m ->
        headerIndex[m.key] = idx
        val st = states[m.key]
        val tabs = st?.tabs.orEmpty()
        val idle = tabs.count { it.status == "idle" }
        idx += 1 + (if (showIdle[m.key] == true) tabs.size else tabs.size - idle) + (if (tabs.isEmpty()) 1 else 0)
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item { TopBar(states) }
        item { StatsRow(states, mode, onMode, deskMode, onDeskMode, full, onFull) }
        if (deskMode) item(key = "desk-note") { DeskNote() }
        // ★나를 기다림 — 자물쇠(권한·질문 대기) 탭은 보기와 상관없이 **맨 위로** 모은다(두 맥 합쳐서).
        //   맥별 구역 안에만 두면 홈맥의 막힘이 노트북 목록 아래에 묻힌다(사용자 지적 2026-09-25).
        val waitingMe = MACHINES.flatMap { m ->
            val st = states[m.key]
            if (st == null || st.error != null) emptyList() else st.tabs.filter { it.status in st.blocked }
        }.sortedByDescending { it.lastActivity ?: 0.0 }
        if (waitingMe.isNotEmpty()) {
            item(key = "wait-head") {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp)
                    .background(C.amber.copy(alpha = .10f), RoundedCornerShape(10.dp))
                    .border(1.dp, C.amber.copy(alpha = .4f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Lic("lock-keyhole", C.amber, 16.dp)
                    Text("나를 기다림", color = C.amberText, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                    Text("${waitingMe.size}개", color = C.amberText, fontSize = 12.5.sp)
                }
            }
            items(waitingMe, key = { "wait-${it.machine.key}-${it.surfaceId}" }) { t ->
                TabCard(t, false, sel?.surfaceId == t.surfaceId && sel.machineKey == t.machine.key, showMachine = true,
                    onLongClick = { onLongPick(Sel(t.machine.key, t.surfaceId)) }) {
                    onPick(Sel(t.machine.key, t.surfaceId))
                }
            }
        }
        // ★진행 중 · 방금 끝남 — 자물쇠 다음 우선순위(사용자 요청 2026-09-25). 두 맥 합쳐 맨 위쪽에.
        //   순서: 작업 중 → 뒤에서 진행 → 방금 끝남(입력 대기로 바뀐 지 JUST_DONE_SEC 이내, 최근 것부터)
        val nowSec = System.currentTimeMillis() / 1000.0
        val live = MACHINES.flatMap { m ->
            val st = states[m.key]
            if (st == null || st.error != null) emptyList() else st.tabs.filter { isLive(it, nowSec) }
        }.sortedWith(compareBy<Tab> { liveRank(it) }.thenByDescending { it.lastActivity ?: 0.0 })
        if (live.isNotEmpty()) {
            item(key = "live-head") {
                val run = live.count { it.status == "running" }
                val wf = live.count { it.status == "workflow" }
                val bg = live.count { it.status == "background" }
                val done = live.size - run - wf - bg
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusIcon("running", 15.dp)
                    Text("진행 중 · 방금 끝남", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(listOfNotNull(run.takeIf { it > 0 }?.let { "작업 중 $it" },
                        wf.takeIf { it > 0 }?.let { "워크플로 $it" }, bg.takeIf { it > 0 }?.let { "뒤에서 $it" },
                        done.takeIf { it > 0 }?.let { "방금 끝남 $it" }).joinToString(" · "), color = C.dim, fontSize = 12.sp)
                }
            }
            items(live, key = { "live-${it.machine.key}-${it.surfaceId}" }) { t ->
                TabCard(t, false, sel?.surfaceId == t.surfaceId && sel.machineKey == t.machine.key, showMachine = true,
                    justDone = t.status == "waiting", onLongClick = { onLongPick(Sel(t.machine.key, t.surfaceId)) }) {
                    onPick(Sel(t.machine.key, t.surfaceId)) }
            }
        }
        if (mode == "window") {
            MACHINES.forEach { m -> windowBoard(m, trees[m.key], states[m.key], sel, onAction) }
            item { Spacer(Modifier.size(40.dp)) }
            return@LazyColumn
        }
        MACHINES.forEach { m ->
            val st = states[m.key]
            val stale = st?.error != null
            // 서버 statusOrder 와 같은 줄(작업중 → 워크플로 → 뒤에서). 서버에 상태가 늘면 여기도 본다.
            val order = listOf("permission", "question", "running", "workflow", "background",
                               "waiting", "idle", "unknown")
            val tabs = st?.tabs.orEmpty().sortedWith(
                compareBy<Tab> { order.indexOf(it.status).let { i -> if (i < 0) 99 else i } }
                    .thenByDescending { it.lastActivity ?: 0.0 })
            val idle = tabs.count { it.status == "idle" }
            item(key = "h-${m.key}") {
                MachineHeader(m, st, if (showIdle[m.key] == true) 0 else idle) {
                    showIdle[m.key] = !(showIdle[m.key] ?: false)
                }
            }
            // 상태별 구역 — «◌ 작업 중 2개» 머리 아래 카드(.nav-grp)
            order.forEach { s ->
                if (s in (st?.blocked ?: emptySet()) && st?.error == null) return@forEach   // 맨 위 «나를 기다림»에 있다
                val group = tabs.filter { it.status == s && !isLive(it, System.currentTimeMillis() / 1000.0) }
                if (group.isEmpty()) return@forEach
                val hideIdle = s == "idle" && showIdle[m.key] != true
                item(key = "sh-${m.key}-$s") {
                    StatusHead(s, group.size, trailing = if (s == "idle") (if (hideIdle) "펼치기" else "접기") else null,
                        onClick = if (s == "idle") ({ showIdle[m.key] = !(showIdle[m.key] ?: false) }) else null)
                }
                if (!hideIdle) items(group, key = { "${m.key}-${it.surfaceId}" }) { t ->
                    TabCard(t, stale, sel?.surfaceId == t.surfaceId && sel.machineKey == m.key,
                        onLongClick = { onLongPick(Sel(m.key, t.surfaceId)) }) {
                        onPick(Sel(m.key, t.surfaceId))
                    }
                }
            }
            if (st != null && tabs.isEmpty() && st.connected) {
                item { Text("살아 있는 claude 탭 없음", color = C.muted, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            }
        }
        item { Spacer(Modifier.size(40.dp)) }
    }
}

/** 통계 줄 + 보기 전환 — 대시보드 두 번째 줄(«☑ 창별 보기 · Claude 탭 50개 · 막힘 0 · …»).
 *  full != null 이면(펼친 화면) «전체 화면» 칩을 단다. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun StatsRow(states: Map<String, MachineState>, mode: String, onMode: (String) -> Unit, deskMode: Boolean,
             onDeskMode: (Boolean) -> Unit, full: Boolean?, onFull: () -> Unit) {
    // 통계 줄 + 보기 전환 — 대시보드 두 번째 줄(«☑ 창별 보기 · Claude 탭 50개 · 막힘 0 · …»)
    val all = MACHINES.flatMap { states[it.key]?.tabs.orEmpty() }
    FlowRow(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically) {
        CheckChip("창별 보기", mode == "window") { onMode(if (mode == "window") "status" else "window") }
        DeskChip(deskMode) { onDeskMode(!deskMode) }
        if (full != null) CheckChip("전체 화면", full) { onFull() }
        // 통계는 항목마다 따로 둔다 — 한 Row 로 묶으면 좁은 폭에서 마지막 항목이 세로로 꺾인다(실측)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Claude 탭", color = C.dim, fontSize = 13.sp)
            Text("${all.size}개", color = C.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        val blocked = all.count { it.status == "permission" || it.status == "question" }
        val nWf = all.count { it.status == "workflow" }
        val nBg = all.count { it.status == "background" }
        // 워크플로·뒤에서 진행은 **있을 때만** 적는다 — 늘 있는 상태가 아니라서 0 을 박아 두면
        // 줄만 길어지고, 반대로 숫자가 뜨는 것 자체가 신호가 된다(관제실 요약줄과 같은 규칙).
        listOfNotNull(Triple("permission", "막힘", blocked),
            Triple("running", "작업중", all.count { it.status == "running" }),
            Triple("workflow", "워크플로", nWf).takeIf { nWf > 0 },
            Triple("background", "뒤에서", nBg).takeIf { nBg > 0 },
            Triple("waiting", "대기", all.count { it.status == "waiting" }),
            Triple("idle", "유휴", all.count { it.status == "idle" })).forEach { (s, lb, n) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                StatusIcon(s, 13.dp)
                Text("$lb $n", color = if (s == "permission" && n > 0) C.amber else C.dim, fontSize = 13.sp, maxLines = 1,
                    fontWeight = if (s == "permission" && n > 0) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

/** 맥 화면 따라가기 안내 줄. */
@Composable
fun DeskNote() {
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp)
        .background(C.accentTint, RoundedCornerShape(10.dp))
        .border(1.dp, C.accent.copy(alpha = .4f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Lic("app-window", C.accent, 15.dp)
        Text("맥 화면 따라가기 — 탭을 누르면 맥의 cmux 가 그 탭으로 넘어갑니다 · 길게 누르면 폰에서 열기",
            color = C.accent, fontSize = 12.5.sp, lineHeight = 17.sp)
    }
}

/** «방금 끝남» — 턴이 끝나 입력 대기로 바뀐 지 이 초 이내. */
const val JUST_DONE_SEC = 600.0

fun isLive(t: Tab, nowSec: Double) = t.status == "running" || t.status == "workflow" ||
    t.status == "background" ||
    (t.status == "waiting" && t.lastActivity != null && nowSec - t.lastActivity < JUST_DONE_SEC)

fun liveRank(t: Tab) = when (t.status) { "running" -> 0; "workflow" -> 1; "background" -> 2; else -> 3 }

/** 대시보드 머리 — 로고 «>» · cmux 관제실 · 연결 · 테마 버튼(시스템 → 밝게 → 어둡게). */
@Composable
fun TopBar(states: Map<String, MachineState>) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxWidth().background(C.bg)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.cmux_mark), null,
                Modifier.size(22.dp))
            Text("cmux 관제실", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp)
            val up = MACHINES.count { states[it.key]?.connected == true }
            Dot(if (up > 0) C.green else C.red)
            Text(if (up == MACHINES.size) "소켓 제어 가능" else "연결 $up/${MACHINES.size}", color = C.dim, fontSize = 12.5.sp)
            Spacer(Modifier.weight(1f))
            // 테마 — 대시보드 theme.js 와 같은 세 상태 순환
            val eff = if (C.dark) "moon" else "sun"
            Row(Modifier.border(1.dp, C.border, RoundedCornerShape(8.dp))
                .clickable {
                    C.mode = when (C.mode) { "system" -> "light"; "light" -> "dark"; else -> "system" }
                    ctx.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE).edit().putString("theme", C.mode).apply()
                }.padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Lic(eff, C.text, 16.dp)
                if (C.mode == "system") Text("자동", color = C.dim, fontSize = 11.sp)
            }
        }
        val modes = MACHINES.mapNotNull { m -> states[m.key]?.sendMode?.let { m.label to it } }
        val sendNote = when {
            modes.isEmpty() -> ""
            modes.all { sendOpen(it.second) } -> " · 보내기 열림"
            modes.none { sendOpen(it.second) } -> " · 읽기 전용"
            else -> " · 보내기 " + modes.joinToString(" ") { "${it.first} ${if (sendOpen(it.second)) "열림" else "닫힘"}" }
        }
        Text("빌드 ${BuildConfig.BUILD_TIME}$sendNote", color = C.dim, fontSize = 11.5.sp,
            modifier = Modifier.padding(start = 45.dp, bottom = 8.dp))
        Box(Modifier.fillMaxWidth().size(1.dp).background(C.border))
    }
}

/** 맥 화면 따라가기 전환 — 켜지면 에메랄드로 채운다(지금 누르면 맥이 움직인다는 걸 한눈에). */
@Composable
fun DeskChip(on: Boolean, onClick: () -> Unit) {
    Row(Modifier.background(if (on) C.accent else C.panel, RoundedCornerShape(8.dp))
        .border(1.dp, if (on) C.accent else C.border, RoundedCornerShape(8.dp))
        .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Lic("app-window", if (on) C.onAccent else C.text, 15.dp)
        Text(if (on) "맥 화면 따라가기 켜짐" else "맥 화면 따라가기", color = if (on) C.onAccent else C.text, fontSize = 13.5.sp,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** 대시보드의 체크 버튼(«☑ 창별 보기»). */
@Composable
fun CheckChip(label: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.background(C.panel, RoundedCornerShape(8.dp)).border(1.dp, C.border, RoundedCornerShape(8.dp))
        .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(17.dp).background(if (on) C.accent else C.raised, RoundedCornerShape(4.dp))
            .border(1.dp, if (on) C.accent else C.border, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            if (on) Text("✓", color = C.onAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(label, color = C.text, fontSize = 13.5.sp)
    }
}

/** 화면에 그릴 줄. 이어진 도구 호출들은 한 줄로 접는다. */
sealed class Row2 {
    data class One(val item: Item) : Row2()
    data class Tools(val items: List<Item>) : Row2()
}

fun group(items: List<Item>): List<Row2> {
    val out = mutableListOf<Row2>()
    var run = mutableListOf<Item>()
    for (it in items) {
        if (it.kind == "tool") { run.add(it); continue }
        if (run.isNotEmpty()) { out.add(Row2.Tools(run)); run = mutableListOf() }
        out.add(Row2.One(it))
    }
    if (run.isNotEmpty()) out.add(Row2.Tools(run))
    return out
}

@Composable
fun ChatScreen(m: Machine, surfaceId: String, tab: Tab?, sendMode: String?, active: Boolean, showBack: Boolean,
               onBack: () -> Unit) {
    var items by remember(surfaceId) { mutableStateOf<List<Item>>(emptyList()) }
    var before by remember(surfaceId) { mutableStateOf<Long?>(null) }
    var err by remember(surfaceId) { mutableStateOf<String?>(null) }
    var loadingOlder by remember(surfaceId) { mutableStateOf(false) }
    var olderFailed by remember(surfaceId) { mutableStateOf(false) }  // 실패하면 저절로 다시 부르지 않는다(버튼으로만)
    var screenMode by remember(surfaceId) { mutableStateOf(false) }
    var prompt by remember(surfaceId) { mutableStateOf<Prompt?>(null) }
    var follow by remember(surfaceId) { mutableStateOf(true) }     // 맨 아래 따라가기
    var unseen by remember(surfaceId) { mutableStateOf(0) }        // 따라가기를 멈춘 동안 새로 온 줄 수
    var autoScrolling by remember(surfaceId) { mutableStateOf(false) }  // 앱이 스스로 내리는 중 — 사람 스크롤로 치지 않는다
    var busy by remember(surfaceId) { mutableStateOf(false) }
    var actMsg by remember(surfaceId) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // 사람을 기다리는 프롬프트는 화면에서 읽는다(서브에이전트의 권한 요청은 transcript 에 없다 — 에이전트 screenprompt.py).
    LaunchedEffect(surfaceId, active) {
        while (active) {
            if (!busy) withContext(Dispatchers.IO) { runCatching { Api.prompt(m, surfaceId) } }
                .onSuccess { p -> if (p?.sig != prompt?.sig) actMsg = null; prompt = p }
            delay(3000)
        }
    }

    fun act(block: () -> org.json.JSONObject, after: (org.json.JSONObject) -> Unit = {}, done: (Boolean) -> Unit = {}) {
        busy = true
        actMsg = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(block) }
            r.onSuccess { after(it) }.onFailure { e -> actMsg = e.message }
            // 답한 뒤에는 바로 화면을 다시 읽어 시트를 닫거나 다음 요청으로 바꾼다
            withContext(Dispatchers.IO) { runCatching { Api.prompt(m, surfaceId) } }.onSuccess { prompt = it }
            busy = false
            done(r.isSuccess)
        }
    }

    // 사람이 스크롤하면 그 끝 위치로 따라가기를 정한다 — 위로 올리면 멈추고, 맨 아래로 내리면 다시 따라간다.
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (moving, fwd) -> if (moving && !autoScrolling) { follow = !fwd; if (!fwd) unseen = 0 } }
    }

    // 이전 페이지를 앞에 붙인다. 보던 줄이 튀지 않게 늘어난 행 수만큼 스크롤 위치를 밀어 준다.
    fun loadOlder() {
        val b = before ?: return
        if (loadingOlder) return
        loadingOlder = true
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { Api.messages(m, surfaceId, before = b, limit = 120) } }
                .onSuccess { p ->
                    // 그새 꼬리 갱신이 통째로 갈아 끼웠으면(커서가 바뀜) 이 페이지는 버린다 — 틈을 조용히 메우지 않는다
                    if (before == b) {
                        val idx = listState.firstVisibleItemIndex
                        val off = listState.firstVisibleItemScrollOffset
                        val grown = group(p.items + items).size - group(items).size
                        items = p.items + items; before = p.before
                        if (grown > 0) {
                            autoScrolling = true
                            try { listState.scrollToItem(idx + grown, off) } finally { autoScrolling = false }
                        }
                    }
                }
                .onFailure { e -> err = e.message; olderFailed = true }
            loadingOlder = false
        }
    }

    // 맨 위 근처(머리 줄 + 2행)가 보이면 이전 대화를 저절로 이어 붙인다 — «이전 대화 불러오기»를 누를 필요 없이
    // 위로 올리기만 하면 처음까지 간다(사용자 요청 2026-09-25). 한 번에 다 받지 않는 건 긴 세션의 셀룰러 비용 때문.
    LaunchedEffect(surfaceId, active) {
        androidx.compose.runtime.snapshotFlow {
            Triple(listState.firstVisibleItemIndex <= 2 && items.isNotEmpty() && !olderFailed, before, loadingOlder)
        }.collect { (nearTop, b, loading) -> if (active && nearTop && b != null && !loading) loadOlder() }
    }

    // 최신 페이지를 4초마다 다시 받아 꼬리만 갈아 끼운다. 이어 붙일 자리를 못 찾으면(그새 많이 쌓임)
    // 통째로 바꾸고 커서도 새로 받는다 — 틈을 조용히 메우지 않는다.
    LaunchedEffect(surfaceId, active) {
        var first = true
        while (active) {
            val r = withContext(Dispatchers.IO) { runCatching { Api.messages(m, surfaceId) } }
            r.onSuccess { p ->
                err = null
                val old = items
                val head = p.items.firstOrNull()
                val at = if (head == null) -1 else old.indexOfFirst { it.id == head.id && it.kind == head.kind }
                if (old.isEmpty() || at < 0) { items = p.items; before = p.before }
                else items = old.subList(0, at) + p.items
                val grown = group(items).size - group(old).size
                // ⚠️ 예전엔 «지금 맨 아래인가»(canScrollForward)로 따라 내려갔다. 질문 시트가 사라지는 등 목록 높이가
                //    바뀌면 마지막 줄이 살짝 가려져 «맨 아래 아님»이 되고, 그 뒤로는 새 내용이 아래에 붙기만 하고
                //    화면은 멈춰 있었다(사용자 신고 2026-09-25). 이제는 **사람이 위로 올렸을 때만** 따라가기를 멈춘다.
                if (first || follow) {
                    val last = listState.layoutInfo.totalItemsCount - 1
                    if (last > 0) { autoScrolling = true; try { listState.scrollToItem(last) } finally { autoScrolling = false } }
                    unseen = 0
                } else if (grown > 0) unseen += grown
                first = false
            }.onFailure { e -> err = e.message }
            delay(4000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (showBack) {
                Text("‹", color = C.text, fontSize = 30.sp,
                    modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 14.dp, vertical = 2.dp))
            } else Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tab?.title ?: "(목록에서 사라짐)", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(tab?.statusLabel ?: "상태 모름", m.label, tab?.let { "${it.windowLabel} › ${it.workspaceTitle}" })
                        .joinToString(" · "),
                    color = statusColor(tab?.status ?: "unknown"), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // 전체 기록 — 기록 앱에서 이 세션의 이어진 사슬 전체·이름·태그·폴더(D7 ②). 기록 앱이 없거나 세션 id 를 모르면 숨긴다
            val hctx = androidx.compose.ui.platform.LocalContext.current
            val hasHistory = remember { Links.historyInstalled(hctx) }
            val hsid = tab?.sessionId
            if (hasHistory && hsid != null) {
                Text("전체 기록", color = C.text, fontSize = 13.sp,
                    modifier = Modifier.border(1.dp, C.border, RoundedCornerShape(10.dp))
                        .clickable { Links.openHistory(hctx, hsid) }.padding(horizontal = 10.dp, vertical = 8.dp))
                Spacer(Modifier.width(6.dp))
            }
            TextSizeButton()
            Spacer(Modifier.width(8.dp))
            Text(if (screenMode) "대화" else "화면", color = C.text, fontSize = 13.sp,
                modifier = Modifier.border(1.dp, C.border, RoundedCornerShape(10.dp))
                    .clickable { screenMode = !screenMode }.padding(horizontal = 12.dp, vertical = 8.dp))
        }
        Box(Modifier.fillMaxWidth().size(1.dp).background(C.border))
        // 읽는 영역 — 글자 배율(«가» 버튼 · 두 손가락 핀치)을 여기에만 건다. 제목 줄은 그대로.
        val tsCtx = androidx.compose.ui.platform.LocalContext.current
        Box(Modifier.weight(1f).fillMaxWidth().pinchTextScale(tsCtx)) {
            ScaledText {
                Column(Modifier.fillMaxSize()) {

                if (err != null) {
                    Text("대화를 못 읽었습니다: $err", color = C.bad, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                }

                if (screenMode) {
                    // 지금 화면 + 에이전트가 적립한 지난 화면(ScreenLog.kt). 권한·질문 시트는 아래 그대로
                    ScreenLogView(m, surfaceId, active, Modifier.weight(1f).fillMaxWidth())
                } else {
                    val rows = group(items)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                                when {
                                    before == null && items.isNotEmpty() -> Text("대화의 처음입니다", color = C.muted, fontSize = 12.sp)
                                    // 새 claude 는 첫 메시지 전까지 대화 기록 파일이 없다(에이전트 notStarted)
                                    before == null && err == null -> Text("새 세션입니다 — 첫 메시지를 보내면 대화가 여기에 보입니다",
                                        color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 24.dp))
                                    before != null -> Text(if (loadingOlder) "불러오는 중…" else "이전 대화 불러오기",
                                        color = C.muted, fontSize = 12.sp,
                                        // 보통은 저절로 불린다(위 LaunchedEffect). 버튼은 오류 뒤 다시 시도용으로 남긴다
                                        modifier = Modifier.border(1.dp, C.line, RoundedCornerShape(16.dp))
                                            .clickable(enabled = !loadingOlder) { olderFailed = false; loadOlder() }
                                            .padding(horizontal = 14.dp, vertical = 7.dp))
                                }
                            }
                        }
                        items(rows) { r -> ChatRow(r) }
                        item { Spacer(Modifier.size(8.dp)) }
                    }
                    if (!follow && unseen > 0) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("새 메시지 $unseen ↓", color = C.onAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 6.dp).background(C.accent, RoundedCornerShape(16.dp))
                                .clickable {
                                    follow = true; unseen = 0
                                    scope.launch { listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) }
                                }.padding(horizontal = 14.dp, vertical = 7.dp))
                    }
                }

                val p = prompt
                when (p?.kind) {
                    "permission" -> PermissionSheet(p, sendMode, busy, actMsg) { n ->
                        act({ Api.answerPermission(m, surfaceId, p.sig, n) },
                            after = { r -> if (!r.optBoolean("ok")) actMsg = "보냈지만 화면의 요청이 그대로입니다 — 화면 원문을 확인하세요" })
                    }
                    "question" -> {
                        // 정본은 transcript 의 답 대기 질문. 없으면(서브에이전트 등) 화면에서 읽은 현재 질문 하나로.
                        val pending = items.lastOrNull { it.kind == "question" && it.status == "pending" }
                        val specs = specsFromTranscript(pending?.questions).takeIf { it.isNotEmpty() }
                            ?.takeIf { sp -> sp.any { it.question.replace(Regex("\\s+"), "").startsWith(
                                (p.question ?: "").replace(Regex("\\s+"), "").take(20)) } }
                        QuestionSheet(specs ?: listOf(specFromScreen(p)), fromScreen = specs == null, sendMode, busy, actMsg,
                            screenQuestions = p.questionCount) { ans ->
                            act({ Api.answerQuestion(m, surfaceId, p.sig, ans) })
                        }
                    }
                    "review" -> ReviewSheet(p, sendMode, busy, actMsg) { n ->
                        act({ Api.answerReview(m, surfaceId, p.sig, n) },
                            after = { r -> if (!r.optBoolean("ok")) actMsg = "보냈지만 검토 화면이 그대로입니다 — 화면 원문을 확인하세요" })
                    }
                    else -> Composer(sendMode, busy, actMsg,
                        onSend = { text, done -> act({ Api.send(m, surfaceId, text) }, done = done) },
                        onKey = { k -> act({ Api.key(m, surfaceId, k) }) })
                }

                }
            }
            PinchBadge(Modifier.align(Alignment.Center))
        }
    }
}

@Composable
fun ChatRow(r: Row2) {
    when (r) {
        is Row2.Tools -> ToolGroup(r.items)
        is Row2.One -> {
            val it = r.item
            when (it.kind) {
                "user" -> Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterEnd) {
                    SelectionContainer {
                        Text(it.text ?: "", color = C.text, fontSize = 14.5.sp, lineHeight = 22.sp,
                            modifier = Modifier.widthIn(max = 520.dp)
                                .background(C.me, RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
                "assistant" -> SelectionContainer(Modifier.padding(horizontal = 16.dp)) {
                    Text(it.text ?: "", color = C.text, fontSize = 14.5.sp, lineHeight = 23.sp)
                }
                "question" -> QuestionCard(it)
                else -> Text(  // system · command
                    (if (it.kind == "command") "⌘ " else "· ") + (it.text ?: ""),
                    color = C.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
    }
}

@Composable
fun ToolGroup(tools: List<Item>) {
    var open by remember { mutableStateOf(false) }
    val names = tools.groupingBy { it.name ?: "?" }.eachCount().entries
        .joinToString(", ") { (n, c) -> if (c > 1) "$n ×$c" else n }
    val pending = tools.any { it.status == "pending" }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        .background(C.panel, RoundedCornerShape(10.dp))
        .border(1.dp, if (pending) C.accent else C.panel2, RoundedCornerShape(10.dp))
        .clickable { open = !open }.padding(horizontal = 12.dp, vertical = 9.dp)) {
        Text((if (open) "▾ " else "▸ ") + "도구 ${tools.size}개 · $names" + (if (pending) " · 실행 중" else ""),
            color = if (pending) C.green else C.dim, fontSize = 12.5.sp,
            maxLines = if (open) 3 else 1, overflow = TextOverflow.Ellipsis)
        if (open) tools.forEach { t ->
            val mark = when (t.status) { "done" -> "✓"; "error" -> "✗"; "pending" -> "…"; else -> "?" }
            Row(Modifier.padding(top = 6.dp)) {
                Mono("$mark ${t.name}", color = if (t.status == "error") C.bad else C.textCode)
                Hspace(8)
                Mono(t.summary ?: "", color = C.dim)
            }
        }
    }
}

@Composable
fun QuestionCard(it: Item) {
    val qs = it.questions
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        .background(C.blockedBg, RoundedCornerShape(14.dp)).border(1.dp, C.blockedLine, RoundedCornerShape(14.dp))
        .padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (it.status == "done") "질문 — 답함" else "질문 — 답 대기", color = C.blocked,
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (qs != null) for (i in 0 until qs.length()) {
            val q = qs.getJSONObject(i)
            Text(q.optString("question"), color = C.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            val opts = q.optJSONArray("options")
            if (opts != null) for (j in 0 until opts.length()) {
                val o = opts.getJSONObject(j)
                Text("${j + 1}. ${o.optString("label")}", color = C.amberText, fontSize = 13.5.sp)
                val d = o.optString("description")
                if (d.isNotBlank()) Text(d, color = C.muted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp))
            }
        }
        if (it.answer != null) Text("→ ${it.answer}", color = C.muted, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

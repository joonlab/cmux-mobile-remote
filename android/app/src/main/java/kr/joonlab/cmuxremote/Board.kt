package kr.joonlab.cmuxremote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joonlab.core.MACHINES
import kr.joonlab.core.Lic

/**
 * 전체 화면 창별 보드 — 관제실 웹 대시보드의 창별 컬럼 보드를 펼친 폴드 화면에 그대로.
 * 창 하나 = 세로 한 열(열마다 따로 위아래 스크롤). 한 화면에 세로 자세 2열 · 가로 자세 3열,
 * 옆으로 밀면 다음 창들이 열 단위로 딱 맞춰 넘어온다. 두 맥의 창을 노트북 → 홈맥 순으로 잇는다.
 * «나를 기다림»은 보드 위 가로 줄로 남긴다(맨 위로 모으는 규칙 — 2026-09-25).
 */
@Composable
fun BoardScreen(states: Map<String, MachineState>, trees: Map<String, TreeState>, sel: Sel?, mode: String,
                onMode: (String) -> Unit, deskMode: Boolean, onDeskMode: (Boolean) -> Unit, onExitFull: () -> Unit,
                onAction: (TreeAction) -> Unit, onLongPick: (Sel) -> Unit, onPick: (Sel) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cols = if (maxWidth > maxHeight) 3 else 2
        val gutter = 8.dp
        val colW = (maxWidth - gutter * (cols + 1)) / cols
        Column(Modifier.fillMaxSize()) {
            TopBar(states)
            StatsRow(states, mode, onMode, deskMode, onDeskMode, full = true, onFull = onExitFull)
            if (deskMode) DeskNote()
            val waitingMe = MACHINES.flatMap { m ->
                val st = states[m.key]
                if (st == null || st.error != null) emptyList() else st.tabs.filter { it.status in st.blocked }
            }.sortedByDescending { it.lastActivity ?: 0.0 }
            if (waitingMe.isNotEmpty()) {
                Row(Modifier.padding(start = 16.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Lic("lock-keyhole", C.amber, 15.dp)
                    Text("나를 기다림", color = C.amberText, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                    Text("${waitingMe.size}개", color = C.amberText, fontSize = 12.sp)
                }
                LazyRow(contentPadding = PaddingValues(horizontal = gutter / 2)) {
                    items(waitingMe, key = { "bw-${it.machine.key}-${it.surfaceId}" }) { t ->
                        androidx.compose.foundation.layout.Box(Modifier.width(colW + gutter)) {
                            TabCard(t, false, sel?.surfaceId == t.surfaceId && sel.machineKey == t.machine.key, showMachine = true,
                                onLongClick = { onLongPick(Sel(t.machine.key, t.surfaceId)) }) {
                                onPick(Sel(t.machine.key, t.surfaceId))
                            }
                        }
                    }
                }
            }
            val wins = MACHINES.flatMap { m -> trees[m.key]?.windows.orEmpty().map { m to it } }
            if (wins.isEmpty()) {
                Text("창 목록을 불러오는 중…", color = C.dim, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                return@Column
            }
            val rowState = rememberLazyListState()
            LazyRow(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), state = rowState,
                contentPadding = PaddingValues(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(gutter),
                flingBehavior = rememberSnapFlingBehavior(rowState)) {
                items(wins, key = { (m, w) -> "bc-${m.key}-${w.id}" }) { (m, w) ->
                    Column(Modifier.width(colW).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        // 두 맥 모두 «창 1»이 있다 → 열 머리에 맥 이름
                        val st = states[m.key]
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Lic(if (m.key == "home") "house" else "app-window", C.dim, 13.dp)
                            Text(m.label, color = C.dim, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                            if (st?.error != null) Text("연결 끊김 — 직전 상태", color = C.red, fontSize = 11.5.sp,
                                modifier = Modifier.background(C.red.copy(alpha = .08f), RoundedCornerShape(5.dp))
                                    .border(1.dp, C.red.copy(alpha = .3f), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp))
                        }
                        // WinPanel 은 좌우 12dp 여백을 스스로 둔다 → 열 안에서는 음수 여백 대신 그대로 쓴다
                        WinPanel(m, w, sel, onAction)
                        Spacer(Modifier.size(40.dp))
                    }
                }
            }
        }
    }
}

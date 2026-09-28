package kr.joonlab.core

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 이 폭 이상이면 칸을 나눈다(폴드를 펼친 화면). */
val WIDE = 600.dp

/**
 * 레일 옆 칸 하나의 폭·접힘. SharedPreferences(prefsName) 의 "${key}W" · "${key}Folded" 에 저장한다
 * (관제실 목록은 key = "list" → 예전 키 listW·listFolded 그대로라 설정이 이어진다).
 * 자세(가로/세로)별로 따로 두려면 key 에 자세를 붙여 부른다.
 */
class PaneState(private val prefs: SharedPreferences, private val key: String, defaultW: Float) {
    var w by mutableFloatStateOf(prefs.getFloat("${key}W", defaultW))
    var folded by mutableStateOf(prefs.getBoolean("${key}Folded", false))
    var dragging by mutableStateOf(false)
        internal set

    fun toggle() { folded = !folded; prefs.edit().putBoolean("${key}Folded", folded).apply() }
    internal fun save() { prefs.edit().putBoolean("${key}Folded", folded).putFloat("${key}W", w).apply() }
}

@Composable
fun rememberPaneState(key: String, defaultW: Float, prefsName: String = "ui"): PaneState {
    val ctx = LocalContext.current
    return remember(key, prefsName) { PaneState(ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE), key, defaultW) }
}

/**
 * 칸 | 레일 | 나머지. 레일 — **탭 = 접기/펼치기, 좌우로 끌기 = 폭 조절**. foldBelow 밑으로 끌면 접히고,
 * 접힌 레일을 오른쪽으로 끌면 다시 펼친다. 나머지 칸에는 최소 minRest 를 남긴다.
 * railExtra 는 접혀 있을 때 화살표 아래에 붙는다(관제실: 막힌 탭 수).
 */
@Composable
fun SplitPane(
    state: PaneState,
    totalW: Dp,
    railW: Dp = 30.dp,
    minW: Float = 260f,
    foldBelow: Float = 200f,
    minRest: Dp = 320.dp,
    railExtra: @Composable ColumnScope.() -> Unit = {},
    pane: @Composable () -> Unit,
    rest: @Composable BoxScope.() -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        val maxW = (totalW - railW - minRest).value.coerceAtLeast(minW)
        val shownW = state.w.coerceIn(minW, maxW)
        if (!state.folded) Box(Modifier.width(shownW.dp).fillMaxHeight()) { pane() }
        Rail(state, maxW, railW, minW, foldBelow, railExtra)
        Box(Modifier.weight(1f).fillMaxHeight()) { rest() }
    }
}

@Composable
private fun Rail(state: PaneState, maxW: Float, railW: Dp, minW: Float, foldBelow: Float,
                 railExtra: @Composable ColumnScope.() -> Unit) {
    val c = LocalPalette.current
    Column(Modifier.width(railW).fillMaxHeight().background(if (state.dragging) c.accentTint else c.panel)
        .border(width = 1.dp, color = if (state.dragging) c.accent.copy(alpha = .5f) else c.border, shape = RoundedCornerShape(0.dp))
        .pointerInput(maxW) {
            var w = 0f
            detectHorizontalDragGestures(
                // 시작 폭은 누른 순간의 값으로 — 직전 끌기로 바뀐 폭에서 이어 간다
                onDragStart = { state.dragging = true; w = if (state.folded) 0f else state.w.coerceIn(minW, maxW) },
                onDragEnd = {
                    state.dragging = false
                    if (w < foldBelow) { state.folded = true } else { state.folded = false; state.w = w.coerceIn(minW, maxW) }
                    state.save()
                },
                onDragCancel = { state.dragging = false },
            ) { ch, dx ->
                ch.consume()
                w = (w + dx / density).coerceIn(0f, maxW)
                if (w >= foldBelow) { state.folded = false; state.w = w.coerceAtLeast(minW) } else state.folded = true
            }
        }
        .clickable { state.toggle() },
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.size(14.dp))
        Text(if (state.folded) "›" else "‹", color = c.accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (state.folded) railExtra()
        // 끌 수 있다는 표시 — 가운데 점 세로줄
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(5) { Box(Modifier.size(4.dp).background(if (state.dragging) c.accent else c.dim.copy(alpha = .5f), CircleShape)) }
        }
        Spacer(Modifier.weight(1f))
    }
}

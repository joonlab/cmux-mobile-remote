package kr.joonlab.core

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 대화 화면 글자 크기. 제목 줄은 그대로 두고 **읽는 영역**(대화·시트·입력창·셸 화면)만 키운다.
 * 조절 경로 둘: 머리의 «가» 버튼(− / % / + / 기본) · 대화 영역 두 손가락 핀치.
 * 배율은 fontScale 로 건다 — sp 로 쓴 글자만 커지고 dp 로 잡은 여백·아이콘은 그대로라 줄 간격이 무너지지 않는다.
 */
object TextScale {
    const val MIN = .85f
    const val MAX = 1.6f
    var v by mutableStateOf(1f)
    var pinching by mutableStateOf(false)
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        v = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getFloat("textScale", 1f).coerceIn(MIN, MAX)
    }

    fun set(ctx: Context, x: Float) {
        v = ((x.coerceIn(MIN, MAX) * 20).roundToInt() / 20f)        // 5% 단위로 저장
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putFloat("textScale", v).apply()
    }

    val pct get() = (v * 100).roundToInt()
}

/** 이 안의 sp 글자에 배율을 건다. */
@Composable
fun ScaledText(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { TextScale.load(ctx) }
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * TextScale.v)) { content() }
}

/**
 * 두 손가락 핀치로 글자 크기. 한 손가락 이벤트는 건드리지 않으므로 스크롤·탭은 그대로 된다.
 * 손가락 둘이 닿은 동안만 소비한다.
 */
fun Modifier.pinchTextScale(ctx: Context): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var scale = TextScale.v
        do {
            // Initial 단계 — 자식(대화 목록)보다 먼저 받아, 두 손가락일 때 목록이 같이 스크롤되지 않게 먹는다
            val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            if (ev.changes.count { it.pressed } >= 2) {
                val z = ev.calculateZoom()
                if (abs(z - 1f) > .001f) {
                    scale = (scale * z).coerceIn(TextScale.MIN, TextScale.MAX)
                    TextScale.v = scale
                    TextScale.pinching = true
                }
                ev.changes.forEach { it.consume() }
            }
        } while (ev.changes.any { it.pressed })
        if (TextScale.pinching) { TextScale.set(ctx, scale); TextScale.pinching = false }
    }
}

/** 핀치하는 동안(그리고 손을 뗀 뒤 0.7초) 가운데 뜨는 «글자 120%». */
@Composable
fun PinchBadge(modifier: Modifier = Modifier) {
    val c = LocalPalette.current
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(TextScale.pinching) {
        if (TextScale.pinching) show = true
        else if (show) { delay(700); show = false }
    }
    if (show) Box(modifier, contentAlignment = Alignment.Center) {
        Text("글자 ${TextScale.pct}%", color = c.onAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.background(c.accent.copy(alpha = .92f), RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp, vertical = 9.dp))
    }
}

/** 머리의 «가» 버튼 → 팝업(가− · 110% · 가+ · 기본). 누를 때마다 10%. */
@Composable
fun TextSizeButton() {
    val c = LocalPalette.current
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        Text("가", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.size(40.dp).border(1.dp, c.border, RoundedCornerShape(10.dp))
                .background(if (TextScale.v != 1f) c.accentTint else c.bg, RoundedCornerShape(10.dp))
                .clickable { open = true }.padding(top = 8.dp))
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = c.raised) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StepBtn("가", 13, enabled = TextScale.v > TextScale.MIN + .001f) { TextScale.set(ctx, TextScale.v - .1f) }
                Text("${TextScale.pct}%", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, modifier = Modifier.width(58.dp))
                StepBtn("가", 20, enabled = TextScale.v < TextScale.MAX - .001f) { TextScale.set(ctx, TextScale.v + .1f) }
                Text("기본", color = if (TextScale.v != 1f) c.accent else c.dim, fontSize = 13.sp,
                    modifier = Modifier.clickable(enabled = TextScale.v != 1f) { TextScale.set(ctx, 1f) }
                        .padding(horizontal = 10.dp, vertical = 12.dp))
            }
            Text("대화 영역에서 두 손가락으로 벌리거나 오므려도 됩니다", color = c.dim, fontSize = 11.5.sp,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp))
        }
    }
}

@Composable
private fun StepBtn(label: String, size: Int, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalPalette.current
    Box(Modifier.size(48.dp).background(if (enabled) c.panel2 else c.panel, RoundedCornerShape(12.dp))
        .border(1.dp, c.border, RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center) {
        // 버튼 글자는 배율을 받지 않게 dp 기준으로 고정한다 — 크기를 바꾸는 버튼이 같이 커지면 누르기 어렵다
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, 1f)) {
            Text(label, color = if (enabled) c.text else c.dim, fontSize = size.sp, fontWeight = FontWeight.Bold)
        }
    }
}

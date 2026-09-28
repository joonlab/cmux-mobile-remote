package kr.joonlab.core

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ── lucide 아이콘(대시보드 icons.js 에서 생성한 IconsGen.kt) ──
private val VEC = HashMap<String, ImageVector>()

fun lucide(name: String): ImageVector? = strokeIcon("lucide:$name", LUCIDE_PATHS[name])

/** 24×24 선 아이콘(stroke 2, round) — path 목록으로 한 번 만들어 key 로 캐시한다. 기록 앱의 Heroicons 도 이걸 쓴다. */
fun strokeIcon(key: String, paths: List<String>?): ImageVector? {
    VEC[key]?.let { return it }
    if (paths == null) return null
    val b = ImageVector.Builder(key, 24.dp, 24.dp, 24f, 24f)
    paths.forEach { d ->
        b.addPath(PathParser().parsePathString(d).toNodes(), stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }
    return b.build().also { VEC[key] = it }
}

@Composable
fun Lic(name: String, color: Color = LocalPalette.current.dim, size: Dp = 16.dp, spin: Boolean = false,
        modifier: Modifier = Modifier) {
    val v = lucide(name) ?: return
    var m = modifier.size(size)
    if (spin) {
        val a by rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f,
            infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "spin")
        m = m.rotate(a)
    }
    Icon(v, null, tint = color, modifier = m)
}

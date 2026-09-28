package kr.joonlab.core

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat

/**
 * core 컴포넌트가 쓰는 색 — 앱이 주입한다(관제실 = Emerald Noir `C`).
 * 값은 getter 로 읽는다: 앱 쪽 구현이 [ThemeMode.dark] 를 읽으면 테마를 바꿀 때 읽은 자리만 다시 그려진다.
 */
interface CorePalette {
    val bg: Color
    val panel: Color
    val panel2: Color
    val raised: Color
    val border: Color
    val text: Color
    val dim: Color
    val accent: Color
    val accentTint: Color
    val onAccent: Color
}

val LocalPalette = staticCompositionLocalOf<CorePalette> { error("CoreTheme 로 CorePalette 를 주입하지 않았다") }

/** 테마는 셋을 돈다: system(폰 설정 추종) → light → dark. 대시보드 theme.js 와 같다. */
object ThemeMode {
    var mode by mutableStateOf("system")      // system · light · dark
    var sysDark by mutableStateOf(false)
    val dark get() = mode == "dark" || (mode == "system" && sysDark)

    fun cycle(ctx: Context, prefsName: String = "ui") {
        mode = when (mode) { "system" -> "light"; "light" -> "dark"; else -> "system" }
        ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString("theme", mode).apply()
    }
}

val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
)

/**
 * 앱의 뿌리에서 한 번 — 색 주입 + 기본 글꼴 Pretendard + 저장된 테마 복원 + 상태·내비 바 글자색을 테마에 맞춤.
 * 테마 설정은 SharedPreferences(prefsName) 의 "theme" 키. 저장값이 없을 때만 defaultMode(기록 앱은 웹 뷰어처럼 light).
 */
@Composable
fun CoreTheme(palette: CorePalette, prefsName: String = "ui", defaultMode: String = "system", content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    ThemeMode.sysDark = isSystemInDarkTheme()
    LaunchedEffect(Unit) {
        ThemeMode.mode = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString("theme", defaultMode) ?: defaultMode
    }
    val view = LocalView.current
    LaunchedEffect(ThemeMode.dark) {
        (ctx as? Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !ThemeMode.dark; isAppearanceLightNavigationBars = !ThemeMode.dark
            }
        }
    }
    CompositionLocalProvider(LocalPalette provides palette, LocalTextStyle provides TextStyle(fontFamily = Pretendard)) {
        content()
    }
}

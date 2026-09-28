package kr.joonlab.cmuxremote

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.joonlab.core.Machine

/** 적립 화면의 한 덩어리. key 는 파일 안 바이트 오프셋 — 앞뒤로 붙여도 보던 자리가 안 튄다. */
private data class LogChunk(val key: Long, val text: String)

private const val CHUNK_LINES = 80

/** start 오프셋에서 시작하는 text 를 CHUNK_LINES 줄씩 자른다. 끝의 개행 하나는 덩어리 구분이라 뗀다. */
private fun split(text: String, start: Long): List<LogChunk> {
    if (text.isEmpty()) return emptyList()
    val out = mutableListOf<LogChunk>()
    var off = start
    for (block in text.removeSuffix("\n").split("\n").chunked(CHUNK_LINES)) {
        val t = block.joinToString("\n")
        out.add(LogChunk(off, t))
        off += (t + "\n").toByteArray(Charsets.UTF_8).size
    }
    return out
}

/**
 * «화면» 보기 — 에이전트가 적립한 지난 화면(screenlog) + 지금 화면.
 *
 * claude 는 alt-screen 이라 터미널이 지난 출력을 안 들고 있다(실측 2026-09-25). 그래서 맥 에이전트가 화면을 계속
 * 찍어 위로 밀려 난 줄을 쌓아 두고, 여기서는 그걸 지금 화면 위에 이어 붙인다. 아래가 지금, 위로 올리면 과거.
 * 적립은 에이전트가 이 탭을 찍기 시작한 뒤부터라 그 전 화면은 없다 — 대화 보기가 그 몫이다.
 */
@Composable
fun ScreenLogView(m: Machine, surfaceId: String, active: Boolean, modifier: Modifier = Modifier) {
    var screen by remember(surfaceId) { mutableStateOf("") }
    var chunks by remember(surfaceId) { mutableStateOf<List<LogChunk>>(emptyList()) }
    var start by remember(surfaceId) { mutableStateOf<Long?>(null) }   // 받아 둔 가장 옛 오프셋. null = 아직 안 받음
    var end by remember(surfaceId) { mutableStateOf(0L) }
    var enabled by remember(surfaceId) { mutableStateOf(true) }
    var loadingOlder by remember(surfaceId) { mutableStateOf(false) }
    var olderFailed by remember(surfaceId) { mutableStateOf(false) }
    var logErr by remember(surfaceId) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun loadTail() {
        val p = Api.screenlog(m, surfaceId)
        chunks = split(p.text, p.start); start = p.start; end = p.end; enabled = p.enabled
    }

    LaunchedEffect(surfaceId, active) {
        while (active) {
            withContext(Dispatchers.IO) { runCatching { Api.screen(m, surfaceId) } }
                .onSuccess { screen = it }.onFailure { screen = "화면을 못 읽었습니다: ${it.message}" }
            // 적립 기록은 실패해도 지금 화면은 보인다 — 옛 에이전트(엔드포인트 없음)면 조용히 빈 채로
            withContext(Dispatchers.IO) {
                runCatching {
                    if (start == null) loadTail()
                    else {
                        val p = Api.screenlog(m, surfaceId, after = end)
                        if (p.reset) loadTail()
                        else if (p.text.isNotEmpty()) { chunks = chunks + split(p.text, p.start); end = p.end }
                    }
                }
            }.onSuccess { logErr = null }.onFailure { logErr = it.message }
            delay(3000)
        }
    }

    fun loadOlder() {
        val s = start ?: return
        if (loadingOlder || s <= 0L) return
        loadingOlder = true
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { Api.screenlog(m, surfaceId, before = s) } }
                .onSuccess { p -> if (start == s) { chunks = split(p.text, p.start) + chunks; start = p.start } }
                .onFailure { olderFailed = true }
            loadingOlder = false
        }
    }

    // 맨 위(reverseLayout 이라 마지막 인덱스)가 보이면 더 옛 기록을 저절로 붙인다
    LaunchedEffect(surfaceId, active) {
        snapshotFlow {
            val info = listState.layoutInfo
            val top = (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 2
            Triple(top && !olderFailed, start, loadingOlder)
        }.collect { (top, s, loading) -> if (active && top && s != null && s > 0L && !loading) loadOlder() }
    }

    // reverseLayout: 인덱스 0 이 맨 아래(지금 화면). 처음 열면 지금 화면에 붙어 있고, 위로 올리면 과거.
    LazyColumn(modifier, state = listState, reverseLayout = true, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item(key = "screen") {
            SelectionContainer(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Mono(screen.ifEmpty { "읽는 중…" }, size = 11)
            }
        }
        item(key = "now") {
            Text("── 지금 화면 ──", color = C.muted, fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
        }
        items(chunks.asReversed(), key = { it.key }) { c ->
            SelectionContainer(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Mono(c.text, color = C.muted, size = 11)
            }
        }
        item(key = "head") {
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                val s = start
                when {
                    !enabled -> Text("이 맥 에이전트는 화면 적립이 꺼져 있습니다(CMR_SCREENLOG=off)", color = C.muted, fontSize = 12.sp)
                    // 옛 에이전트(엔드포인트 없음·404)거나 네트워크 오류 — «읽는 중»에 멈춰 있지 않게 이유를 보인다
                    s == null && logErr != null -> Text("지난 화면을 못 읽었습니다 — 이 맥 에이전트가 화면 적립 전 버전일 수 있습니다\n($logErr)",
                        color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
                    s == null -> Text("지난 화면 읽는 중…", color = C.muted, fontSize = 12.sp)
                    s == 0L && chunks.isEmpty() -> Text("아직 쌓인 지난 화면이 없습니다 — 에이전트가 이 탭을 찍기 시작한 뒤부터 쌓입니다.\n그 전 내용은 «대화» 보기에서 처음까지 볼 수 있습니다.",
                        color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
                    s == 0L -> Text("적립된 화면의 처음입니다 — 그 전은 «대화» 보기에서", color = C.muted, fontSize = 12.sp)
                    else -> Text(if (loadingOlder) "불러오는 중…" else "이전 화면 불러오기", color = C.muted, fontSize = 12.sp,
                        modifier = Modifier.border(1.dp, C.line, RoundedCornerShape(16.dp))
                            .clickable(enabled = !loadingOlder) { olderFailed = false; loadOlder() }
                            .padding(horizontal = 14.dp, vertical = 7.dp))
                }
            }
        }
    }
}

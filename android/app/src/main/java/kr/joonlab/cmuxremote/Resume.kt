package kr.joonlab.cmuxremote

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.joonlab.core.MACHINES
import kr.joonlab.core.Machine
import kr.joonlab.core.str
import org.json.JSONObject

/**
 * 앱끼리 잇는 규약. 링크 대상은 늘 명시한다 — 맥과 surface/sid 를 URI 에 싣는다(«현재»·«첫 번째» 금지).
 *   cmuxremote://tab/{machine}/{surfaceId}   기록 앱의 «살아 있음» 배지 → 그 탭 대화
 *   cmuxremote://resume/{sid}?title=…        기록 앱의 «이어가기» → 맥 고르는 시트(기본값 없음)
 *   cchistory://session/{sid}                관제실 대화 머리의 «전체 기록» → 기록 앱
 */
sealed class Link {
    data class OpenTab(val machineKey: String, val surfaceId: String) : Link()
    data class Resume(val sid: String, val title: String?) : Link()
}

private val SID_RE = Regex("^[0-9a-fA-F-]{8,64}$")

fun parseLink(u: Uri?): Link? {
    if (u == null || u.scheme != "cmuxremote") return null
    val p = u.pathSegments
    return when (u.host) {
        "tab" -> {
            val m = p.getOrNull(0)?.takeIf { k -> MACHINES.any { it.key == k } } ?: return null
            val s = p.getOrNull(1)?.takeIf { SID_RE.matches(it) } ?: return null
            Link.OpenTab(m, s.uppercase())
        }
        "resume" -> p.getOrNull(0)?.takeIf { SID_RE.matches(it) }?.let { Link.Resume(it.lowercase(), u.getQueryParameter("title")) }
        else -> null
    }
}

object Links {
    private fun historyUri(sid: String) = Uri.parse("cchistory://session/$sid")

    /** 기록 앱이 깔려 있나 — 없으면 버튼을 숨긴다(누르면 죽는 버튼을 두지 않는다). 매니페스트 <queries> 필요. */
    fun historyInstalled(ctx: Context) =
        Intent(Intent.ACTION_VIEW, historyUri("00000000-0000-0000-0000-000000000000")).resolveActivity(ctx.packageManager) != null

    fun openHistory(ctx: Context, sid: String) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, historyUri(sid)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** hand 의 맥 이름(hand.hosts label) → 앱의 맥. */
fun machineOfHand(host: String?): Machine? = when (host) {
    "laptop" -> MACHINES.firstOrNull { it.key == "laptop" }
    "homemac", "home" -> MACHINES.firstOrNull { it.key == "home" }
    else -> null
}

/**
 * 이어가기 — 두 맥 모두에 resume-check 를 물은 뒤 사람이 맥을 **직접** 고른다(기본값 없음).
 * 한 맥에서 이미 돌고 있으면(cmux 탭 · hand owner 의 pid) 다른 맥 버튼은 막는다 — 두 맥 운용 규칙 1.
 * 에이전트도 resume 을 받으면 같은 검사를 다시 한다(폰이 본 뒤로 바뀌었을 수 있다).
 */
@Composable
fun ResumeDialog(link: Link.Resume, states: Map<String, MachineState>, onDismiss: () -> Unit, onOpen: (Sel) -> Unit) {
    val checks = remember(link.sid) { mutableStateMapOf<String, Result<JSONObject>>() }
    var busy by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var skip by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun check() {
        val jobs = MACHINES.map { m -> m to scope.async(Dispatchers.IO) { runCatching { Api.resumeCheck(m, link.sid) } } }
        jobs.forEach { (m, j) -> checks[m.key] = j.await() }
    }
    LaunchedEffect(link.sid) { check() }

    // 어디서 돌고 있나 — cmux 탭(live) 또는 hand owner 의 살아 있는 pid. 한 곳이라도 있으면 새로 띄우지 않는다
    val runningOn: Pair<Machine, String>? = MACHINES.firstNotNullOfOrNull { m ->
        val d = checks[m.key]?.getOrNull() ?: return@firstNotNullOfOrNull null
        if (!d.isNull("live")) return@firstNotNullOfOrNull m to "${m.label} cmux 에서 실행 중"
        val ow = d.optJSONObject("owner")
        if (ow != null && ow.optBoolean("alive") && !ow.isNull("alive"))
            machineOfHand(ow.str("host"))?.let { it to "${it.label} 에서 진행 중(pid ${ow.optInt("pid")})" }
        else null
    }
    val name = link.title ?: checks.values.firstNotNullOfOrNull { r -> r.getOrNull()?.str("name") } ?: link.sid.take(8)

    DialogBox("이어가기", onDismiss) {
        Text(name, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Note("세션 ${link.sid.take(8)} — 고른 맥의 cmux 에 새 워크스페이스를 만들고 claude --resume 으로 되살립니다")
        if (runningOn != null) Note("⛔ ${runningOn.second} — 한 세션은 한 맥에서만 진행합니다", color = C.amber)
        MACHINES.forEach { m ->
            val r = checks[m.key]
            val d = r?.getOrNull()
            val mode = states[m.key]?.sendMode
            Column(Modifier.fillMaxWidth().background(C.panel2, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(m.label, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                when {
                    r == null -> Note("확인하는 중…")
                    d == null -> Note(r.exceptionOrNull()?.message ?: "확인 실패", color = C.red)
                    else -> {
                        d.str("cwd")?.let { Note(shortPath(it) + if (d.optBoolean("cwdExists")) "" else " (이 맥에 없음)") }
                        val action = d.optString("action")
                        val live = d.optJSONObject("live")
                        Note(d.optString("reason"), color = when (action) { "resume" -> C.green; "open" -> C.accent; "confirm" -> C.amber; else -> C.red })
                        val otherRunning = runningOn != null && runningOn.first.key != m.key
                        when {
                            live != null -> ActBtn("이 탭 열기", busy == null) {
                                onOpen(Sel(m.key, live.getString("surfaceId").uppercase()))
                            }
                            action == "resume" || action == "confirm" -> {
                                val ok = !otherRunning && runningOn == null && sendOpen(mode)
                                ActBtn(if (busy == m.key) "만드는 중…" else if (action == "confirm") "확인 없이 ${m.label}에서 이어가기" else "${m.label}에서 이어가기",
                                    ok && busy == null) {
                                    busy = m.key; msg = null
                                    scope.launch {
                                        withContext(Dispatchers.IO) { runCatching { Api.resume(m, link.sid, force = action == "confirm", skip = skip) } }
                                            .onSuccess { res ->
                                                val sf = res.optJSONArray("surfaces")?.optString(0)
                                                if (sf.isNullOrBlank()) msg = "워크스페이스는 만들었지만 탭 id 를 못 받았습니다"
                                                else if (res.optBoolean("already")) onOpen(Sel(m.key, sf.uppercase()))
                                                else onOpen(Sel(m.key, sf.uppercase(), "shell", "↩ $name", autoChat = true))
                                            }
                                            .onFailure { e -> msg = e.message; check() }
                                        busy = null
                                    }
                                }
                                if (!sendOpen(mode)) Note(sendClosedReason(mode))
                            }
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pick("권한 확인 그대로", !skip) { skip = false }
            Pick("권한 건너뛰기", skip) { skip = true }
        }
        if (msg != null) Note(msg!!, color = C.red)
        Text("닫기", color = C.muted, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp))
    }
}

@Composable
private fun ActBtn(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(label, color = if (enabled) C.onAccent else C.dim, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().background(if (enabled) C.accent else C.panel, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp))
}

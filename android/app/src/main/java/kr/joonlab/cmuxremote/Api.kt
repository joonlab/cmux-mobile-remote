package kr.joonlab.cmuxremote

import kr.joonlab.core.Http
import kr.joonlab.core.Machine
import kr.joonlab.core.dbl
import kr.joonlab.core.str
import org.json.JSONArray
import org.json.JSONObject

data class Tab(
    val machine: Machine,
    val surfaceId: String,
    val sessionId: String?,
    val status: String,
    val statusLabel: String,
    val statusSource: String,
    val title: String,
    val windowLabel: String,
    val workspaceTitle: String,
    val lastActivity: Double?,
    val lastPromptText: String?,
    val askText: String?,
    val promptLine: String?,   // 권한 대기면 화면에서 읽은 한 줄(«Bash · python …») — 에이전트 /sessions
    val surfaceRef: String? = null,
    val workspaceColor: String? = null,   // 카드 왼쪽 3px 띠 — cmux 에서 사람이 칠한 색
    val groupName: String? = null,
    val groupIcon: String? = null,
    val groupColor: String? = null,
    /** 다이내믹 워크플로가 도는 탭이면 값이 있다(안 돌면 null). */
    val workflow: Wf? = null,
    /** 돌고 있는 백그라운드 서브에이전트(Agent 도구). 본 세션은 턴을 끝내고 이걸 기다린다 → 상태 «뒤에서 진행». */
    val bgAgents: List<BgAgent> = emptyList(),
)

data class BgAgent(val description: String?, val agentType: String?, val startedAt: Double?)

/** 돌고 있는 다이내믹 워크플로. 에이전트 여럿이 단계를 밟는 긴 작업이라 탭 하나로는 안 보인다. */
data class Wf(
    val runId: String,
    val name: String?,
    val phase: String?,
    val agentsRunning: Int,
    val agentsTotal: Int,
    val labels: List<String>,
    val startedAt: Double?,
    val runsLive: Int,
)

/** 한 맥의 마지막 폴링 결과. 실패해도 직전 목록(tabs)은 남기고 «지금 상태 모름»으로 드러낸다. */
data class MachineState(
    val tabs: List<Tab> = emptyList(),
    val error: String? = null,
    val okAt: Long? = null,
    val version: String? = null,
    val warnings: List<String> = emptyList(),
    val blocked: Set<String> = setOf("permission", "question"),
    val sendMode: String? = null,
) {
    val connected get() = error == null && okAt != null
}

data class Item(
    val kind: String,          // user · assistant · tool · question · system · command
    val id: String?,
    val ts: Double?,
    val text: String?,
    val name: String?,
    val summary: String?,
    val status: String?,       // tool/question: done · error · pending · null(모름)
    val questions: JSONArray?,
    val answer: String?,
)

data class Page(val items: List<Item>, val before: Long?, val status: String?)

/** 적립 화면 한 조각. start == 0 이면 처음까지 읽은 것. reset 이면 파일이 잘려 처음부터 다시 받아야 한다. */
data class LogPage(val text: String, val start: Long, val end: Long, val reset: Boolean, val enabled: Boolean)

/** 화면에서 읽은 «사람을 기다리는 프롬프트». 에이전트 screenprompt.py 의 dict 를 그대로 싣는다. */
data class Opt(val n: Int, val label: String, val desc: String, val checked: Boolean?)

data class Prompt(
    val kind: String,            // permission · question · review
    val sig: String,
    val title: String?,
    val question: String?,
    val body: String?,
    val truncatedTop: Boolean,
    val options: List<Opt>,
    val multi: Boolean,
    val otherN: Int?,
    val answers: List<Pair<String, String>>,   // 검토 화면: 질문 → 답
    val questionCount: Int = 0,                // 질문 화면 맨 위 탭 수(= AskUserQuestion 질문 수)
) {
    companion object {
        fun of(o: JSONObject?): Prompt? {
            if (o == null) return null
            val arr = o.optJSONArray("options") ?: JSONArray()
            return Prompt(
                kind = o.optString("kind"),
                sig = o.optString("sig"),
                title = if (o.isNull("title")) null else o.optString("title", null),
                question = if (o.isNull("question")) null else o.optString("question", null),
                body = if (o.isNull("body")) null else o.optString("body", null),
                truncatedTop = o.optBoolean("truncatedTop"),
                options = (0 until arr.length()).map { i ->
                    val x = arr.getJSONObject(i)
                    Opt(x.getInt("n"), x.optString("label"), x.optString("desc"),
                        if (x.isNull("checked")) null else x.optBoolean("checked"))
                },
                multi = o.optBoolean("multi"),
                otherN = if (o.isNull("otherN") || !o.has("otherN")) null else o.optInt("otherN"),
                answers = (o.optJSONArray("answers") ?: JSONArray()).let { a ->
                    (0 until a.length()).map { i -> a.getJSONObject(i).let { it.optString("question") to it.optString("answer") } }
                },
                questionCount = o.optJSONArray("tabs")?.length() ?: 0,
            )
        }
    }
}

/** 창별 보기 — 에이전트 /tree(tree.py). 탭은 셸·브라우저 포함, claude 탭이면 status 가 붙는다. */
data class TreeTab(val surfaceId: String, val type: String, val title: String,
                   val status: String?, val statusLabel: String?, val claudeTitle: String?, val lastActivity: Double?,
                   val ref: String? = null) {
    val isClaude get() = status != null
}
data class Ws(val id: String, val title: String, val color: String?, val cwd: String?, val groupId: String?,
              val isAnchor: Boolean, val selected: Boolean, val tabs: List<TreeTab>)
data class Grp(val id: String, val name: String, val color: String?, val anchor: String, val icon: String? = null,
               val pinned: Boolean = false)
data class Win(val id: String, val label: String, val active: Boolean, val groups: List<Grp>, val workspaces: List<Ws>)
data class TreeState(val windows: List<Win>, val sendMode: String?)

data class Snippet(val keyword: String?, val name: String, val text: String)

/** 질문 하나에 대한 폰의 답. picks 는 transcript 의 label 원문. */
data class QAns(val question: String, val picks: List<String>, val other: String?)

object Api {
    private fun call(url: String, post: JSONObject? = null, readMs: Int = 12000) = Http.call(url, post, readMs)

    private fun get(url: String) = call(url)

    fun health(m: Machine): JSONObject = get("${m.base}/api/v1/health")

    fun sessions(m: Machine): MachineState {
        val d = get("${m.base}/api/v1/sessions")
        val arr = d.getJSONArray("tabs")
        val tabs = (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i)
            Tab(
                machine = m,
                surfaceId = t.getString("surfaceId"),
                sessionId = t.str("sessionId"),
                status = t.optString("status", "unknown"),
                statusLabel = t.optString("statusLabel", "?"),
                statusSource = t.optString("statusSource", ""),
                title = t.str("cleanTitle") ?: "(제목 없음)",
                windowLabel = t.optString("windowLabel", ""),
                workspaceTitle = t.str("workspaceTitle") ?: "",
                lastActivity = t.dbl("lastActivity"),
                lastPromptText = t.str("lastPromptText"),
                askText = t.str("askText"),
                promptLine = t.optJSONObject("prompt")?.let { p ->
                    listOfNotNull(p.str("title"), p.str("line")?.takeIf { it.isNotBlank() }).joinToString(" · ")
                        .ifBlank { p.str("question") }
                },
                surfaceRef = t.str("surfaceRef"),
                workspaceColor = t.str("workspaceColor"),
                groupName = t.optJSONObject("group")?.str("name"),
                groupIcon = t.optJSONObject("group")?.str("icon"),
                groupColor = t.optJSONObject("group")?.str("color"),
                workflow = t.optJSONObject("workflow")?.let { w ->
                    val la = w.optJSONArray("labels")
                    Wf(runId = w.optString("runId", ""), name = w.str("name"), phase = w.str("phase"),
                        agentsRunning = w.optInt("agentsRunning"), agentsTotal = w.optInt("agentsTotal"),
                        labels = (0 until (la?.length() ?: 0)).map { la!!.getString(it) },
                        startedAt = w.dbl("startedAt"), runsLive = w.optInt("runsLive", 1))
                },
                bgAgents = t.optJSONArray("bgAgents")?.let { a ->
                    (0 until a.length()).map { i ->
                        val o = a.getJSONObject(i)
                        BgAgent(o.str("description"), o.str("agentType"), o.dbl("startedAt"))
                    }
                } ?: emptyList(),
            )
        }
        val w = d.optJSONArray("warnings") ?: JSONArray()
        val b = d.optJSONArray("blockedStatuses") ?: JSONArray()
        val v = d.optJSONObject("version")
        return MachineState(
            tabs = tabs,
            okAt = System.currentTimeMillis(),
            version = v?.let { "${it.optString("agent")}/${it.optString("vendor")}" },
            warnings = (0 until w.length()).map { w.getString(it) },
            blocked = (0 until b.length()).map { b.getString(it) }.toSet(),
            sendMode = d.str("send"),
        )
    }

    fun messages(m: Machine, surfaceId: String, before: Long? = null, limit: Int = 40): Page {
        val q = buildString {
            append("?limit=$limit")
            if (before != null) append("&before=$before")
        }
        val d = get("${m.base}/api/v1/sessions/$surfaceId/messages$q")
        val arr = d.getJSONArray("items")
        val items = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Item(
                kind = o.optString("kind"),
                id = o.str("id") ?: o.str("toolUseId"),
                ts = o.dbl("ts"),
                text = o.str("text"),
                name = o.str("name"),
                summary = o.str("summary"),
                status = o.str("status"),
                questions = o.optJSONArray("questions"),
                answer = o.str("answer"),
            )
        }
        return Page(items, if (d.isNull("before")) null else d.getLong("before"), d.str("status"))
    }

    fun screen(m: Machine, surfaceId: String, lines: Int = 120): String =
        get("${m.base}/api/v1/sessions/$surfaceId/screen?lines=$lines").optString("text")

    /** 에이전트가 적립해 둔 옛 화면(screenlog.py). after 가 있으면 그 뒤 새 줄만, 아니면 before(없으면 끝)에서 거꾸로. */
    fun screenlog(m: Machine, surfaceId: String, before: Long? = null, after: Long? = null): LogPage {
        val q = when {
            after != null -> "?after=$after"
            before != null -> "?before=$before"
            else -> ""
        }
        val d = get("${m.base}/api/v1/sessions/$surfaceId/screenlog$q")
        return LogPage(d.optString("text"), d.optLong("start"), d.optLong("end"), d.optBoolean("reset"),
            d.optBoolean("enabled", true))
    }

    fun prompt(m: Machine, surfaceId: String): Prompt? =
        Prompt.of(get("${m.base}/api/v1/sessions/$surfaceId/prompt").optJSONObject("prompt"))

    /** 에이전트가 보내기 직전에 화면을 다시 읽어 sig 가 같을 때만 누른다. 다르면 409. */
    fun answerPermission(m: Machine, surfaceId: String, sig: String, n: Int): JSONObject =
        call("${m.base}/api/v1/sessions/$surfaceId/answer/permission",
            JSONObject().put("sig", sig).put("n", n))

    fun answerReview(m: Machine, surfaceId: String, sig: String, n: Int): JSONObject =
        call("${m.base}/api/v1/sessions/$surfaceId/answer/review", JSONObject().put("sig", sig).put("n", n))

    fun answerQuestion(m: Machine, surfaceId: String, sig: String, answers: List<QAns>): JSONObject =
        call("${m.base}/api/v1/sessions/$surfaceId/answer/question",
            JSONObject().put("sig", sig).put("answers", JSONArray(answers.map { a ->
                JSONObject().put("question", a.question).put("picks", JSONArray(a.picks))
                    .put("other", a.other ?: JSONObject.NULL)
            })), readMs = 90000)       // 단계마다 화면을 다시 읽어 몇 초~수십 초 걸린다

    fun tree(m: Machine): TreeState {
        val d = get("${m.base}/api/v1/tree")
        val wins = d.getJSONArray("windows")
        return TreeState((0 until wins.length()).map { i ->
            val w = wins.getJSONObject(i)
            val gs = w.getJSONArray("groups")
            val wss = w.getJSONArray("workspaces")
            Win(
                id = w.getString("id"), label = w.optString("label"), active = w.optBoolean("active"),
                groups = (0 until gs.length()).map { j ->
                    gs.getJSONObject(j).let { g -> Grp(g.getString("id"), g.optString("name"), g.str("color"), g.optString("anchor", ""), g.str("icon"), g.optBoolean("pinned")) }
                },
                workspaces = (0 until wss.length()).map { j ->
                    val ws = wss.getJSONObject(j)
                    val tabs = ws.getJSONArray("tabs")
                    Ws(
                        id = ws.getString("id"), title = ws.optString("title"), color = ws.str("color"),
                        cwd = ws.str("cwd"), groupId = ws.str("groupId"), isAnchor = ws.optBoolean("isAnchor"),
                        selected = ws.optBoolean("selected"),
                        tabs = (0 until tabs.length()).map { k ->
                            val t = tabs.getJSONObject(k)
                            val c = t.optJSONObject("claude")
                            TreeTab(t.getString("surfaceId"), t.optString("type"), t.str("title") ?: "",
                                c?.optString("status"), c?.optString("statusLabel"), c?.str("cleanTitle"),
                                c?.dbl("lastActivity"), t.str("ref"))
                        },
                    )
                },
            )
        }, d.str("send"))
    }

    fun snippets(m: Machine): List<Snippet> {
        val arr = get("${m.base}/api/v1/snippets").getJSONArray("snippets")
        return (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let { Snippet(it.str("keyword")?.ifBlank { null }, it.optString("name"), it.optString("text")) }
        }
    }

    fun newWorkspace(m: Machine, name: String, cwd: String?, command: String?, windowId: String?, groupId: String?): JSONObject =
        call("${m.base}/api/v1/workspaces", JSONObject().put("name", name)
            .put("cwd", cwd ?: JSONObject.NULL).put("command", command ?: JSONObject.NULL)
            .put("windowId", windowId ?: JSONObject.NULL).put("groupId", groupId ?: JSONObject.NULL), readMs = 40000)

    fun renameWorkspace(m: Machine, wsId: String, title: String): JSONObject =
        call("${m.base}/api/v1/workspaces/$wsId/rename", JSONObject().put("title", title))

    fun closeWorkspace(m: Machine, wsId: String, confirmTitle: String, claudeCount: Int): JSONObject =
        call("${m.base}/api/v1/workspaces/$wsId/close",
            JSONObject().put("confirmTitle", confirmTitle).put("claudeCount", claudeCount))

    /** 기록 앱 → 이어가기 — 이 맥에서 이 세션을 되살릴 수 있나(읽기). action: open · block · confirm · resume */
    fun resumeCheck(m: Machine, sid: String): JSONObject =
        call("${m.base}/api/v1/history/sessions/$sid/resume-check", readMs = 20000)   // 다른 맥 owner 는 ssh 로 확인(최대 ~8초)

    /** 이 맥 cmux 에 새 워크스페이스 + `claude -r <sid>`. 에이전트가 같은 검사를 다시 하고 막히면 409. */
    fun resume(m: Machine, sid: String, force: Boolean, skip: Boolean): JSONObject =
        call("${m.base}/api/v1/history/sessions/$sid/resume",
            JSONObject().put("force", force).put("skipPermissions", skip), readMs = 40000)

    /** 맥 화면 따라가기 — 그 맥의 cmux 를 이 탭으로(탭 선택 → 앱 최전면 → 창 raise → 검증). */
    fun focus(m: Machine, surfaceId: String): JSONObject =
        call("${m.base}/api/v1/sessions/$surfaceId/focus", JSONObject().put("activate", true))

    fun send(m: Machine, surfaceId: String, text: String): JSONObject =
        call("${m.base}/api/v1/sessions/$surfaceId/send", JSONObject().put("text", text).put("submit", true),
            readMs = 30000)

    fun key(m: Machine, surfaceId: String, key: String): JSONObject =
        call("${m.base}/api/v1/sessions/$surfaceId/key", JSONObject().put("key", key))
}

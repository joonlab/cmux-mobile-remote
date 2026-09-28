"""cmux 모바일 관제실 — 맥 한 대에 하나씩 뜨는 에이전트.

폰 앱이 두 맥의 에이전트에 **각각 직접** 붙는다(허브 없음). 한 대가 꺼져도 다른 한 대는 멀쩡하고,
꺼진 쪽은 앱이 «연결 안 됨»으로 드러낸다.

노출: 127.0.0.1 에만 바인딩하고 `tailscale serve --https=8797` 로만 tailnet 에 내보낸다.
      (0.0.0.0 이면 노트북이 카페 와이파이에 붙었을 때 같은 LAN 에 그대로 열린다.)

쓰기(보내기·답하기·워크스페이스)는 CMR_SEND 로 연다. 기본값은 off(읽기 전용)다.
"""
import os
import socket
import subprocess
import time

from fastapi import FastAPI, HTTPException, Query

import transcript
import tree as treemod
from dash import REPO_DIR, VENDOR, cmux_client, nav

MACHINE = os.environ.get("CMR_MACHINE") or socket.gethostname().split(".")[0]
API = "/api/v1"


def _git_sha(path):
    try:
        return subprocess.run(["git", "-C", path, "rev-parse", "--short", "HEAD"],
                              capture_output=True, timeout=5).stdout.decode().strip() or None
    except Exception:                                          # noqa: BLE001
        return None


# 두 맥의 에이전트 버전이 어긋나면 같은 화면이 다르게 보인다 → 응답마다 실어 앱이 드러내게 한다.
VERSION = {"agent": _git_sha(REPO_DIR), "vendor": _git_sha(VENDOR)}

app = FastAPI(title="cmux mobile remote agent")

# 폰에 내보낼 탭 필드. nav.collect() 의 원본은 크다(wsTabs·notif 원문 등) — 셀룰러에서 가볍게.
_TAB_FIELDS = (
    "surfaceId", "surfaceRef", "sessionId", "liveSessionId", "sessionIdSource",
    "status", "statusLabel", "statusSource", "title", "cleanTitle",
    "windowId", "windowRef", "windowIndex", "windowLabel",
    "workspaceId", "workspaceRef", "workspaceTitle", "workspaceIndex", "workspaceColor",
    "group", "isActiveTab", "lastActivity", "activitySource",
    "lastPromptText", "lastPromptAt", "askText", "cwd",
    "bgShells", "bgJustDone", "titleRenamedByUser",
    # 다이내믹 워크플로가 도는 탭이면 {runId,name,phase,agentsRunning,agentsTotal,labels,startedAt}.
    # 안 도는 탭은 None 이라 셀룰러 비용이 거의 없다.
    "workflow",
    # 백그라운드 서브에이전트가 도는 탭이면 [{agentId,description,agentType,startedAt,lastActivity}]. 없으면 None.
    "bgAgents",
)


def _meta():
    return {"machine": MACHINE, "version": VERSION, "now": time.time()}


def _collect():
    try:
        return nav.collect()
    except Exception as e:                                     # noqa: BLE001
        # 수집 자체가 죽었으면 «0개»가 아니라 실패로 알린다 — 빈 목록은 «탭 없음»으로 읽힌다.
        raise HTTPException(503, f"탭 수집 실패: {e}") from e


def _find_tab(surface_id):
    sid = (surface_id or "").upper()
    for t in _collect()["tabs"]:
        if str(t.get("surfaceId") or "").upper() == sid:
            return t
    raise HTTPException(404, f"살아 있는 claude 탭이 아닙니다: {surface_id}")


@app.get(f"{API}/health")
def health():
    return {**_meta(), "ok": True, "socket": cmux_client.ping(),
            "host": socket.gethostname(), "send": SEND_MODE}


@app.get(f"{API}/sessions")
def sessions():
    d = _collect()
    tabs = [{k: t.get(k) for k in _TAB_FIELDS} for t in d["tabs"]]
    for t in tabs:
        if t["status"] == "permission":
            t["prompt"] = _prompt_brief(t.get("surfaceRef") or t["surfaceId"])
    return {**_meta(), "send": SEND_MODE, "generatedAt": d["generatedAt"], "tabs": tabs, "counts": d["counts"],
            "total": d["total"], "warnings": d["warnings"], "treeStaleSec": d["treeStaleSec"],
            "statusOrder": d["statusOrder"], "blockedStatuses": d["blockedStatuses"],
            "statusLabel": d["statusLabel"], "activeSurfaceId": d["activeSurfaceId"]}


@app.get(API + "/sessions/{surface_id}/messages")
def messages(surface_id: str, before: int | None = None,
             limit: int = Query(40, ge=5, le=200)):
    t = _find_tab(surface_id)
    sid = t.get("sessionId")
    if not sid:
        raise HTTPException(409, "이 탭의 세션 id 를 모릅니다(새로 시작해 제목도 못 가림) "
                                 "— 화면 원문(/screen)만 볼 수 있습니다")
    path = nav.transcript_path(sid)          # 이어진 세션(continued-in)이면 사슬 끝까지 따라간다
    if not path:
        # 실측(2026-09-25): 새로 띄운 claude 는 **첫 메시지를 받기 전까지 .jsonl 을 만들지 않는다.**
        # 살아 있는 탭인데 파일이 없으면 오류가 아니라 «아직 대화 없음»이다 — 폰에 빨간 오류를 띄우지 않는다.
        return {**_meta(), "surfaceId": t["surfaceId"], "sessionId": sid, "transcriptSessionId": None,
                "status": t["status"], "items": [], "before": None, "size": 0, "notStarted": True}
    page = transcript.read_page(path, before=before, limit=limit)
    return {**_meta(), "surfaceId": t["surfaceId"], "sessionId": sid,
            "transcriptSessionId": os.path.basename(path)[:-6], "status": t["status"], **page}


# ─────────────────────────── 쓰기 ───────────────────────────
#
# 이 앱은 **남의 터미널에 글을 쓰는 도구**다. 그래서 쓰기는 세 겹으로 막는다.
#   ① CMR_SEND 모드 — off(기본) / allowlist(CMR_SEND_ALLOW 의 surface UUID + **이 프로세스가 만든 워크스페이스**만) / on
#   ② 표적은 «지금 살아 있는 claude 탭» 또는 «라이브 트리의 터미널 탭(셸)» 이어야 한다. 브라우저 탭은 안 된다
#   ③ 모든 쓰기를 data/audit.jsonl 에 남긴다
#
# ⚠️ surface 가 비면 cmux 는 **호출자 자신의 탭**($CMUX_SURFACE_ID)으로 보낸다. 관제실 _clean_env 가
#    CMUX_* 를 걷어내지만, 빈 값은 여기서 먼저 거부한다.
#
# 실측(2026-09-25, 테스트 워크스페이스 + 테스트 claude):
#   - send_text 안의 \n · \r 은 **둘 다 Enter** 가 된다. 브래킷 페이스트(ESC[200~)로 감싸도,
#     set-buffer + paste-buffer 로 붙여도 마찬가지 → 여러 줄을 그대로 보내면 첫 줄에서 제출된다.
#   - **shift+enter** 는 claude 입력창에서 줄바꿈만 넣는다(제출 안 됨).
#   → 여러 줄 = 줄마다 send_text, 줄 사이 shift+enter, 마지막에 Enter.
#   - send_key 는 숫자를 모른다("1" → Unknown key). 숫자는 send_text 로 보낸다.

import hashlib  # noqa: E402
import json  # noqa: E402
import re  # noqa: E402
import threading  # noqa: E402

from pydantic import BaseModel  # noqa: E402

from dash import DATA_DIR  # noqa: E402

SEND_MODE = os.environ.get("CMR_SEND", "off")
SEND_ALLOW = {s.strip().upper() for s in os.environ.get("CMR_SEND_ALLOW", "").split(",") if s.strip()}
_UUID = re.compile(r"^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}$")
# 폰에서 누를 수 있는 키. ctrl+c 는 두 번이면 claude 가 종료되므로 **셸 탭에만** 허용한다.
KEYS = {"escape", "enter", "shift+tab", "up", "down", "left", "right", "tab", "backspace"}
SHELL_ONLY_KEYS = {"ctrl+c"}
CREATED_WS = set()                 # 이 프로세스가 만든 워크스페이스 — allowlist 모드의 테스트 표적
_SEND_LOCK = threading.Lock()      # 한 탭에 두 메시지가 섞여 들어가지 않게 — 쓰기는 한 번에 하나
AUDIT = os.path.join(DATA_DIR, "audit.jsonl")


def _audit(**kw):
    kw = {"at": time.time(), "machine": MACHINE, **kw}
    with open(AUDIT, "a", encoding="utf-8") as f:
        f.write(json.dumps(kw, ensure_ascii=False) + "\n")


def _send_open():
    if SEND_MODE == "off":
        raise HTTPException(403, "보내기가 꺼져 있습니다(CMR_SEND=off)")


def _write_target(surface_id):
    """쓰기 표적을 확정한다 → (surface UUID, claude 탭 또는 None, 'claude'|'shell').
    확정 못 하면 예외 — 절대 «대충 첫 번째»로 가지 않는다."""
    sid = (surface_id or "").strip().upper()
    if not _UUID.match(sid):
        raise HTTPException(400, "surface 는 UUID 로 명시해야 합니다(빈 값 = 호출자 탭으로 가는 사고)")
    _send_open()
    tabs = {str(t.get("surfaceId") or "").upper(): t for t in _collect()["tabs"]}
    found = treemod.find_surface(treemod.build([]), sid)
    if SEND_MODE == "allowlist" and sid not in SEND_ALLOW and not (found and found[1]["id"] in CREATED_WS):
        raise HTTPException(403, f"허용 목록에 없는 surface 입니다(CMR_SEND=allowlist): {sid}")
    if sid in tabs:
        return sid, tabs[sid], "claude"
    if found and found[2]["type"] == "terminal":
        return sid, None, "shell"
    if SEND_MODE == "allowlist" and sid in SEND_ALLOW:
        return sid, None, "claude"
    raise HTTPException(404, f"살아 있는 터미널 탭이 아닙니다: {sid}")


class SendBody(BaseModel):
    text: str
    submit: bool = True           # False 면 입력창에 넣기만 한다(사람이 폰에서 다시 확인)


class KeyBody(BaseModel):
    key: str


def type_message(sid, text, submit=True, settle=0.15, shell=False):
    """여러 줄 메시지를 claude 입력창에 넣는다. 위 실측 주석 참조.
    셸 탭이면 줄마다 Enter(= 줄마다 한 명령) — 셸에는 «입력창 안 줄바꿈»이 없다."""
    lines = text.replace("\r\n", "\n").replace("\r", "\n").replace("\t", "    ").split("\n")
    for i, ln in enumerate(lines):
        if ln:
            cmux_client.send_text(sid, ln)
            time.sleep(settle)
        if i < len(lines) - 1:
            cmux_client.send_key(sid, "enter" if shell else "shift+enter")
            time.sleep(settle)
    if submit:
        time.sleep(settle)                 # 텍스트보다 Enter 가 먼저 닿는 레이스 방지(관제실 run_in_surface 교훈)
        cmux_client.send_key(sid, "enter")


@app.post(API + "/sessions/{surface_id}/send")
def send(surface_id: str, body: SendBody):
    text = body.text or ""
    if not text.strip():
        raise HTTPException(400, "빈 메시지")
    if len(text) > 20_000:
        raise HTTPException(413, "메시지가 너무 깁니다(20,000자 상한)")
    sid, tab, kind = _write_target(surface_id)
    with _SEND_LOCK:
        try:
            type_message(sid, text, submit=body.submit, shell=kind == "shell")
        except cmux_client.CmuxError as e:
            _audit(kind="send", surface=sid, ok=False, error=str(e)[:300])
            raise HTTPException(502, str(e)) from e
    _audit(kind="send", surface=sid, target=kind, ok=True, submit=body.submit,
           session=(tab or {}).get("sessionId"), chars=len(text),
           sha=hashlib.sha256(text.encode()).hexdigest()[:12], preview=text[:80])
    return {**_meta(), "ok": True, "surfaceId": sid, "lines": text.count("\n") + 1,
            "submitted": body.submit}


@app.post(API + "/sessions/{surface_id}/key")
def key(surface_id: str, body: KeyBody):
    k = (body.key or "").strip().lower()
    if k not in KEYS | SHELL_ONLY_KEYS:
        raise HTTPException(400, f"허용하지 않는 키: {k} (허용: {sorted(KEYS | SHELL_ONLY_KEYS)})")
    sid, tab, kind = _write_target(surface_id)
    if k in SHELL_ONLY_KEYS and kind != "shell":
        raise HTTPException(400, f"{k} 는 셸 탭에만 보낼 수 있습니다(claude 탭은 두 번이면 종료된다)")
    with _SEND_LOCK:
        try:
            cmux_client.send_key(sid, k)
        except cmux_client.CmuxError as e:
            _audit(kind="key", surface=sid, key=k, ok=False, error=str(e)[:300])
            raise HTTPException(502, str(e)) from e
    _audit(kind="key", surface=sid, key=k, ok=True, session=(tab or {}).get("sessionId"))
    return {**_meta(), "ok": True, "surfaceId": sid, "key": k}


def _read_screen(surface, lines=80, scrollback=True):
    args = ["read-screen", "--surface", surface, "--lines", str(lines)]
    if scrollback:
        args.insert(3, "--scrollback")
    try:
        return cmux_client._run(args, timeout=10)
    except cmux_client.CmuxError as e:
        raise HTTPException(502, str(e)) from e


@app.get(API + "/sessions/{surface_id}/screen")
def screen(surface_id: str, lines: int = Query(80, ge=10, le=2000)):
    """터미널 화면 원문. claude 탭은 transcript 대조용, 셸 탭은 이게 곧 화면이다."""
    sid = (surface_id or "").upper()
    found = treemod.find_surface(treemod.build([]), sid)
    if not found or found[2]["type"] != "terminal":
        raise HTTPException(404, f"터미널 탭이 아닙니다: {surface_id}")
    ref = found[2].get("ref") or sid
    SPOOL.viewed[sid] = time.time()
    return {**_meta(), "surfaceId": sid, "surfaceRef": ref, "text": _read_screen(ref, lines)}


# ─────────────────────── 화면 적립(스풀) ───────────────────────
#
# claude 는 alt-screen 이라 read-screen 이 지금 화면(42~52줄)밖에 못 준다(실측). 에이전트가 화면을 계속 찍어
# 위로 밀려 난 줄을 data/screenlog/<surfaceId>.log 에 쌓고, 폰은 «쌓인 것 + 지금 화면»을 이어 보여 준다.
# 권한·질문 경로(_prompt_of)와는 따로 읽는다 — 그쪽 줄 수·sig 를 건드리지 않는다.

import screenlog  # noqa: E402

SPOOL = screenlog.Spool(os.path.join(DATA_DIR, "screenlog"))
SCREENLOG_ON = os.environ.get("CMR_SCREENLOG", "on") != "off"


def _spool_read(ref):
    return cmux_client._run(["read-screen", "--surface", ref], timeout=10)


@app.on_event("startup")
def _spool_start():
    if SCREENLOG_ON:
        threading.Thread(target=screenlog.run_forever, name="screenlog", daemon=True,
                         args=(SPOOL, lambda: nav.collect()["tabs"], _spool_read)).start()


@app.get(API + "/sessions/{surface_id}/screenlog")
def screenlog_page(surface_id: str, before: int | None = None, after: int | None = None,
                   limit: int = Query(256 * 1024, ge=4096, le=2 * 1024 * 1024)):
    """적립된 옛 화면. after=끝 오프셋이면 그 뒤 새 줄만, 아니면 before(없으면 끝)에서 거꾸로 limit 바이트.
    start==0 이면 처음까지 읽은 것. reset 이면 파일이 잘렸다 → 폰은 처음부터 다시 받는다."""
    sid = (surface_id or "").upper()
    try:
        page = SPOOL.read(sid, before=before, after=after, limit=limit)
    except ValueError as e:
        raise HTTPException(400, f"surfaceId 형식이 아닙니다: {surface_id}") from e
    SPOOL.viewed[sid] = time.time()
    return {**_meta(), "surfaceId": sid, "enabled": SCREENLOG_ON, **page}


# ─────────────────────── 프롬프트(권한·질문)에 답하기 ───────────────────────
#
# 권한 요청의 정본은 화면이다(screenprompt.py 머리말 — 서브에이전트의 권한 요청은 transcript 에 없다).
# 원칙: **보내기 직전에 화면을 다시 읽어, 폰이 본 것과 같은 프롬프트(sig)일 때만** 보낸다.
# 질문(AskUserQuestion)은 키 순서가 화면 상태마다 달라서(실측) 정해 둔
# 키 묶음을 한 번에 쏘지 않는다. 한 단계마다 화면을 다시 읽고 다음 키를 고르며, 마지막 검토 화면의
# 답이 폰이 보낸 답과 **같을 때만** «Submit answers» 를 누른다. 다르면 거기서 멈추고 화면을 돌려준다.
#
# 화면은 늘 같은 줄 수로 읽는다 — 줄 수가 다르면 잘리는 위치가 달라 sig 가 흔들린다.

import screenprompt as promptmod  # noqa: E402

PROMPT_LINES = 60
STEP_SETTLE = 0.45                  # 키 하나 뒤 화면이 다시 그려질 때까지(실측 0.3~1s 안쪽)


def _prompt_of(ref):
    return promptmod.parse(_read_screen(ref, PROMPT_LINES, scrollback=False))


_BRIEF_CACHE = {}                   # ref → (at, brief). 목록은 5초마다 불리므로 권한 탭 화면 읽기를 아낀다


def _prompt_brief(ref, ttl=4.0):
    """목록 카드용 한 줄 — «Bash · python train.py …». 못 읽으면 None(카드는 원래 문구로)."""
    hit = _BRIEF_CACHE.get(ref)
    if hit and time.time() - hit[0] < ttl:
        return hit[1]
    try:
        p = _prompt_of(ref)
    except HTTPException:
        p = None
    brief = None
    if p and p["kind"] == "permission":
        first = next((ln.strip() for ln in (p.get("body") or "").splitlines() if ln.strip()), "")
        brief = {"title": p.get("title"), "question": p["question"], "line": first[:160]}
    _BRIEF_CACHE[ref] = (time.time(), brief)
    return brief


@app.get(API + "/sessions/{surface_id}/prompt")
def get_prompt(surface_id: str):
    t = _find_tab(surface_id)
    ref = t.get("surfaceRef") or t["surfaceId"]
    return {**_meta(), "surfaceId": t["surfaceId"], "status": t.get("status"), "prompt": _prompt_of(ref)}


class PermissionAnswer(BaseModel):
    sig: str
    n: int


class QAnswer(BaseModel):
    question: str
    picks: list[str] = []          # 고른 선택지 label(transcript 원문). 단일 선택이면 0~1개
    other: str | None = None       # «기타 — 직접 입력»


class QuestionAnswer(BaseModel):
    sig: str                       # 폰이 본 첫 질문 화면의 sig
    answers: list[QAnswer]


def _write_ref(surface_id):
    """쓰기 표적 확정 + 화면을 읽을 ref. allowlist 의 테스트 surface 는 탭 목록에 없을 수 있다."""
    sid, tab, _kind = _write_target(surface_id)
    return sid, tab, (tab or {}).get("surfaceRef") or sid


@app.post(API + "/sessions/{surface_id}/answer/permission")
def answer_permission(surface_id: str, body: PermissionAnswer):
    return _answer_choice(surface_id, body, "permission")


@app.post(API + "/sessions/{surface_id}/answer/review")
def answer_review(surface_id: str, body: PermissionAnswer):
    """질문 검토 화면(«Submit answers / Cancel»)에서 사람이 직접 고른다. 자동 제출이 대조에 실패해 멈췄을 때의 출구."""
    return _answer_choice(surface_id, body, "review")


def _answer_choice(surface_id, body, kind):
    sid, tab, ref = _write_ref(surface_id)
    with _SEND_LOCK:
        p = _prompt_of(ref)
        if not p or p["kind"] != kind or p["sig"] != body.sig:
            _audit(kind=kind, surface=sid, n=body.n, ok=False, error="stale")
            raise HTTPException(409, {"reason": "화면의 요청이 폰이 본 것과 다릅니다 — 다시 불러오세요",
                                      "prompt": p})
        if body.n not in [o["n"] for o in p["options"]]:
            raise HTTPException(400, f"없는 선택지: {body.n}")
        label = next(o["label"] for o in p["options"] if o["n"] == body.n)
        cmux_client.send_text(sid, str(body.n))        # 실측: 숫자 하나로 Enter 없이 바로 선택된다
        time.sleep(STEP_SETTLE * 2)
        after = _prompt_of(ref)
    gone = not after or after.get("sig") != body.sig
    _audit(kind=kind, surface=sid, n=body.n, label=label[:80], ok=gone,
           session=(tab or {}).get("sessionId"), question=(p.get("question") or "")[:120])
    return {**_meta(), "ok": gone, "chose": {"n": body.n, "label": label}, "after": after}


def _expected_review(a: QAnswer):
    items = list(a.picks)
    if a.other:
        items.append(a.other)
    return ", ".join(items)


def _pick_n(scr, label):
    """transcript 의 label → 화면 번호. 화면 label 은 폭 때문에 잘릴 수 있어 «앞부분 일치»로 찾는다."""
    want = promptmod.nows(label)
    hits = [o["n"] for o in scr["options"] if o["n"] != scr.get("otherN")
            and promptmod.nows(o["label"]) and want.startswith(promptmod.nows(o["label"]))]
    if len(hits) != 1:
        raise _Stop(f"선택지를 화면에서 하나로 못 찾았습니다: {label} → {hits}", scr)
    return hits[0]


class _Stop(Exception):
    def __init__(self, reason, screen=None):
        super().__init__(reason)
        self.reason, self.screen = reason, screen


def _step_question(sid, scr, plan, trace):
    """질문 화면 하나에서 **키 하나**를 고른다. 다 맞으면 다음으로 넘기는 키를 보낸다."""
    a = next((x for qt, x in plan.items() if promptmod.nows(qt).startswith(promptmod.nows(scr["question"]))
              or promptmod.nows(scr["question"]).startswith(promptmod.nows(qt))), None)
    if a is None:
        raise _Stop(f"화면의 질문이 보낸 답에 없습니다: {scr['question']}", scr)

    def send(kind, v):
        if not isinstance(v, str) or not v:
            raise _Stop(f"보낼 값이 비었습니다({v!r}) — 멈췄습니다", scr)
        (cmux_client.send_text if kind == "text" else cmux_client.send_key)(sid, v)
        trace.append(f"{scr['question'][:30]} · {kind}:{v}")

    other_n = scr.get("otherN")
    if a.other and other_n is None:
        raise _Stop("화면에서 «기타» 칸을 못 찾았습니다", scr)
    if sum(1 for t in trace if t.startswith(scr["question"][:30] + " ·")) >= 12:
        raise _Stop("한 질문에서 단계가 너무 많습니다(12) — 멈췄습니다", scr)
    if not scr["multi"]:
        if a.other:
            if scr["cursor"] != other_n:
                return send("text", str(other_n))      # 커서만 «기타»로 간다(입력 모드)
            if scr.get("otherText") is None:
                if "\n" in a.other:
                    raise _Stop("기타 답은 한 줄만 됩니다", scr)
                return send("text", a.other)
            if promptmod.nows(scr["otherText"]) != promptmod.nows(a.other):
                raise _Stop("기타 칸에 이미 다른 글이 있습니다", scr)
            return send("key", "enter")
        if len(a.picks) != 1:
            raise _Stop("단일 선택 질문에는 선택지 하나가 필요합니다", scr)
        return send("text", str(_pick_n(scr, a.picks[0])))   # 실측: 고르면서 다음으로 넘어간다

    want = {_pick_n(scr, lb) for lb in a.picks}
    for o in scr["options"]:
        if o["n"] == other_n:
            continue
        if (o["n"] in want) != bool(o["checked"]):
            return send("text", str(o["n"]))           # 실측: 다중 선택에서 숫자 = 체크 토글, 커서는 그대로
    if other_n is not None:
        oo = next(o for o in scr["options"] if o["n"] == other_n)
        if a.other:
            if scr.get("otherText") is None:
                if scr["cursor"] != other_n:
                    # 숫자로는 커서가 안 간다 → 방향키로 한 칸씩
                    cur = scr["cursor"] if isinstance(scr["cursor"], int) else other_n + 1
                    return send("key", "down" if cur < other_n else "up")
                return send("text", a.other)
            if promptmod.nows(scr["otherText"]) != promptmod.nows(a.other):
                raise _Stop("기타 칸에 이미 다른 글이 있습니다", scr)
            if not oo["checked"]:
                return send("text", str(other_n))
        elif oo["checked"]:
            return send("text", str(other_n))
    if not scr.get("hasSubmitRow"):
        raise _Stop("다중 선택인데 Submit 줄을 못 찾았습니다", scr)
    if scr["cursor"] == "submit":
        return send("key", "enter")                    # 실측: Submit 줄에서 Enter → 다음 질문 또는 검토 화면
    return send("key", "down")


@app.post(API + "/sessions/{surface_id}/answer/question")
def answer_question(surface_id: str, body: QuestionAnswer):
    sid, tab, ref = _write_ref(surface_id)
    plan = {a.question: a for a in body.answers}
    trace, final = [], None
    with _SEND_LOCK:
        first = _prompt_of(ref)
        if not first or first["kind"] != "question" or first["sig"] != body.sig:
            _audit(kind="question", surface=sid, ok=False, error="stale")
            raise HTTPException(409, {"reason": "화면의 질문이 폰이 본 것과 다릅니다 — 다시 불러오세요",
                                      "prompt": first})
        # 폰이 질문 일부만 보냈으면(대화 기록이 늦게 들어와 화면의 현재 질문 하나로 시트를 만든 경우 — 실측 2026-09-25)
        # 앞 질문만 입력하고 뒤에서 멈추면 반쯤 입력된 화면이 남는다 → 키를 하나도 보내기 전에 거절한다
        n_q = len(first.get("tabs") or [])
        if n_q > len(plan):
            _audit(kind="question", surface=sid, ok=False, error="partial", questions=n_q, answers=len(plan))
            raise HTTPException(409, {"reason": f"질문이 {n_q}개인데 답이 {len(plan)}개만 왔습니다 — 아무 키도 보내지 않았습니다. "
                                                "대화 기록이 들어온 뒤(시트가 «질문 1 / {n_q}»로 바뀐 뒤) 다시 보내세요",
                                      "prompt": first})
        scr, seen = first, {}
        try:
            for _ in range(60):
                if scr is None:
                    if final:
                        break
                    # 질문 1개·단일 선택은 숫자 하나로 곧장 제출된다(검토 화면 없음) — 그 경우만 성공
                    if len(plan) == 1 and trace and not trace[-1].endswith(("down", "up")):
                        final = "direct"
                        break
                    raise _Stop("프롬프트가 예상보다 먼저 사라졌습니다")
                if scr["kind"] == "review":
                    # 다중 선택은 화면이 선택지 순서로 늘어놓는다 → 쉼표로 나눈 항목의 정렬 목록으로 대조
                    def norm(s):
                        return sorted(promptmod.nows(s).split(","))
                    got = {promptmod.nows(x["question"]): norm(x["answer"]) for x in scr["answers"]}
                    want = {promptmod.nows(q): norm(_expected_review(a)) for q, a in plan.items()}
                    if got != want:
                        raise _Stop("검토 화면의 답이 보낸 답과 다릅니다 — 제출하지 않았습니다", scr)
                    sub = next(o["n"] for o in scr["options"] if o["label"].startswith("Submit"))
                    cmux_client.send_text(sid, str(sub))
                    trace.append(f"review · text:{sub}")
                    final = "review"
                elif scr["kind"] == "question":
                    _step_question(sid, scr, plan, trace)
                else:
                    raise _Stop("질문이 아닌 프롬프트가 떴습니다", scr)
                # 같은 화면이 계속 돌아오면(키가 안 먹음) 멈춘다
                key = (scr.get("sig"), str(scr.get("cursor")), str([o.get("checked") for o in scr.get("options", [])]),
                       scr.get("otherText"))
                seen[key] = seen.get(key, 0) + 1
                if seen[key] > 3:
                    raise _Stop("같은 화면에서 진전이 없습니다", scr)
                time.sleep(STEP_SETTLE)
                scr = _prompt_of(ref)
                if final and (scr is None or scr["kind"] != "review"):
                    break
            else:
                raise _Stop("단계 상한(60)을 넘었습니다", scr)
        except _Stop as e:
            _audit(kind="question", surface=sid, ok=False, error=e.reason, trace=trace[-20:])
            raise HTTPException(409, {"reason": e.reason, "prompt": e.screen, "trace": trace}) from None
    _audit(kind="question", surface=sid, ok=True, final=final, trace=trace[-20:],
           session=(tab or {}).get("sessionId"),
           answers={a.question[:60]: _expected_review(a)[:120] for a in body.answers})
    return {**_meta(), "ok": True, "final": final, "trace": trace}


# ─────────────────────── 창별 보기 · 워크스페이스 조작 ───────────────────────
#
# 폰에서 새 워크스페이스를 열고(시작 명령 = 예: `claude --dangerously-skip-permissions`), 이름을 바꾸고, 닫는다.
# ⚠️ cmux 의 rename-workspace·close-workspace·workspace-action 은 --workspace 를 빼면 **현재 워크스페이스**로
#    간다(호출자 탭 사고와 같은 모양). 그래서 표적은 늘 라이브 트리에서 확인한 UUID 로 명시한다.
# 닫기는 되돌릴 수 없다(안에서 돌던 claude 가 죽는다) → 폰이 **현재 제목을 그대로 다시 보내야** 닫고,
# claude 탭이 들어 있으면 claudeCount 까지 맞춰 보내야 닫는다. 그룹 앵커(그룹 머리)는 닫지 않는다.


def _tree():
    try:
        return treemod.build(sessions()["tabs"])
    except cmux_client.CmuxError as e:
        raise HTTPException(503, f"cmux 트리를 못 읽었습니다: {e}") from e


@app.get(API + "/tree")
def get_tree():
    return {**_meta(), "send": SEND_MODE, **_tree()}


class NewWorkspace(BaseModel):
    name: str
    cwd: str | None = None
    command: str | None = None        # 새 셸에서 실행할 한 줄(없으면 빈 셸)
    windowId: str | None = None
    groupId: str | None = None


@app.post(API + "/workspaces")
def new_workspace(body: NewWorkspace):
    _send_open()
    name = (body.name or "").strip()
    if not name:
        raise HTTPException(400, "이름이 필요합니다")
    t = _tree()
    win = None
    if body.windowId:
        win = next((w for w in t["windows"] if w["id"] == body.windowId.upper()), None)
        if not win:
            raise HTTPException(404, f"없는 창: {body.windowId}")
    if body.groupId:
        gid = body.groupId.upper()
        if not any(g["id"] == gid for w in t["windows"] for g in w["groups"]):
            raise HTTPException(404, f"없는 그룹: {body.groupId}")
    cwd = os.path.expanduser(body.cwd) if body.cwd else None
    if cwd and not os.path.isdir(cwd):
        raise HTTPException(400, f"없는 폴더: {cwd}")
    cmd = (body.command or "").strip() or None
    if cmd and "\n" in cmd:
        raise HTTPException(400, "시작 명령은 한 줄만 됩니다")
    with _SEND_LOCK:
        try:
            out, ref = cmux_client.new_workspace(
                name=name, cwd=cwd, command=cmd, window=win["id"] if win else None,
                group=body.groupId.upper() if body.groupId else None,
                group_placement="end" if body.groupId else None, focus=False)
        except cmux_client.CmuxError as e:
            _audit(kind="workspace.new", ok=False, name=name, error=str(e)[:300])
            raise HTTPException(502, str(e)) from e
        if not ref:
            raise HTTPException(502, f"새 워크스페이스 ref 를 못 받았습니다: {out[:200]}")
        wt = cmux_client.workspace_tree(ref)
    ws = next((x for w in wt.get("windows", []) for x in w.get("workspaces", []) if x.get("ref") == ref), None)
    wid = str((ws or {}).get("id") or "").upper()
    surfaces = [str(s.get("id")).upper() for p in (ws or {}).get("panes", []) for s in p.get("surfaces", [])]
    if wid:
        CREATED_WS.add(wid)
    _audit(kind="workspace.new", ok=True, name=name, cwd=cwd, command=cmd, window=(win or {}).get("id"),
           group=body.groupId, workspace=wid)
    return {**_meta(), "ok": True, "workspaceId": wid, "ref": ref, "surfaces": surfaces}


def _ws_target(ws_id):
    _send_open()
    wid = (ws_id or "").strip().upper()
    if not _UUID.match(wid):
        raise HTTPException(400, "워크스페이스는 UUID 로 명시해야 합니다(빈 값 = 현재 워크스페이스로 가는 사고)")
    if SEND_MODE == "allowlist" and wid not in CREATED_WS:
        raise HTTPException(403, "allowlist 모드에서는 이 에이전트가 만든 워크스페이스만 바꿀 수 있습니다")
    t = _tree()
    hit = treemod.find_workspace(t, wid)
    if not hit:
        raise HTTPException(404, f"없는 워크스페이스: {ws_id}")
    return wid, hit[0], hit[1]


class Rename(BaseModel):
    title: str


@app.post(API + "/workspaces/{ws_id}/rename")
def rename_workspace(ws_id: str, body: Rename):
    title = (body.title or "").strip()
    if not title or "\n" in title:
        raise HTTPException(400, "한 줄 제목이 필요합니다")
    wid, win, ws = _ws_target(ws_id)
    with _SEND_LOCK:
        try:
            cmux_client._run(["rename-workspace", "--workspace", wid, "--window", win["id"], title], timeout=10)
        except cmux_client.CmuxError as e:
            raise HTTPException(502, str(e)) from e
    _audit(kind="workspace.rename", ok=True, workspace=wid, before=ws["title"], after=title)
    return {**_meta(), "ok": True, "workspaceId": wid, "title": title}


class CloseWs(BaseModel):
    confirmTitle: str                 # 현재 제목 그대로 — 엉뚱한 워크스페이스를 닫지 않게
    claudeCount: int = 0              # 안에 든 claude 탭 수 — 폰이 보고 있던 것과 같아야 닫는다


@app.post(API + "/workspaces/{ws_id}/close")
def close_workspace(ws_id: str, body: CloseWs):
    wid, win, ws = _ws_target(ws_id)
    if ws["isAnchor"]:
        raise HTTPException(400, "그룹 머리(앵커) 워크스페이스는 닫지 않습니다 — 그룹이 깨집니다")
    if (body.confirmTitle or "").strip() != (ws["title"] or "").strip():
        raise HTTPException(409, {"reason": "제목이 지금 워크스페이스와 다릅니다 — 닫지 않았습니다",
                                  "title": ws["title"]})
    n_claude = sum(1 for tb in ws["tabs"] if tb["claude"])
    if body.claudeCount != n_claude:
        raise HTTPException(409, {"reason": f"claude 탭이 {n_claude}개 들어 있습니다 — 확인 후 다시 보내세요",
                                  "claudeCount": n_claude})
    with _SEND_LOCK:
        try:
            cmux_client._run(["close-workspace", "--workspace", wid, "--window", win["id"]], timeout=15)
        except cmux_client.CmuxError as e:
            raise HTTPException(502, str(e)) from e
    CREATED_WS.discard(wid)
    _audit(kind="workspace.close", ok=True, workspace=wid, title=ws["title"], claude=n_claude,
           tabs=[tb["title"] for tb in ws["tabs"]][:20])
    return {**_meta(), "ok": True, "workspaceId": wid}


# ─────────────────────── 스니펫(Raycast 내보내기) ───────────────────────
#
# 원천: data/snippets.json — Raycast «Export Snippets» 결과를 그대로 둔다(git 제외, chmod 600 권장).
# ⚠️ 내보내기에 API 키·비밀번호가 든 스니펫이 있으면 그대로 폰에 실린다(tailnet 안에서만).
#    싣기 싫은 스니펫은 파일에서 빼 두세요. 파일이 없으면 빈 목록을 준다.
# 펼치기(placeholder 해석)는 폰이 한다: {cursor} {clipboard} {date …} {argument …}.

SNIPPETS = os.path.join(DATA_DIR, "snippets.json")


@app.get(API + "/snippets")
def snippets():
    try:
        with open(SNIPPETS, encoding="utf-8") as f:
            raw = json.load(f)
        mtime = os.path.getmtime(SNIPPETS)
    except FileNotFoundError:
        return {**_meta(), "snippets": [], "mtime": None}
    seen, out = set(), []
    for sn in raw:
        key = (sn.get("keyword"), sn.get("name"), sn.get("text"))
        if key in seen:
            continue                    # 내보내기에 같은 스니펫이 두 번 들어 있다(;cc 등)
        seen.add(key)
        out.append({"keyword": sn.get("keyword"), "name": sn.get("name") or "", "text": sn.get("text") or ""})
    return {**_meta(), "snippets": out, "mtime": mtime}


# ─────────────────────── 맥 화면 따라가기(데스크탑 모드) ───────────────────────
#
# 폰에서 탭을 누르면 **그 맥의 cmux 가 그 탭으로 넘어간다** — 데스크탑에서 일하며 폰을 진행 상황판으로 쓸 때.
# 순서는 관제실 /api/cmux/focus 와 같다(그쪽 주석이 이유다):
#   ① 탭 선택 → ② cmux 앱 최전면 → ③ 원하는 창 raise → ④ 실제로 그 탭이 활성인지 확인, 아니면 한 번 더.
# 사용자의 화면을 바꾸는 동작이라 보내기(CMR_SEND)가 꺼져 있으면 막고, 감사 로그에 남긴다.


class FocusBody(BaseModel):
    activate: bool = True             # cmux 앱을 macOS 최전면으로


@app.post(API + "/sessions/{surface_id}/focus")
def focus(surface_id: str, body: FocusBody):
    _send_open()
    sid = (surface_id or "").strip().upper()
    if not _UUID.match(sid):
        raise HTTPException(400, "surface 는 UUID 로 명시해야 합니다")
    found = treemod.find_surface(treemod.build([]), sid)
    if not found:
        raise HTTPException(404, f"지금 cmux 에 없는 탭입니다: {surface_id}")
    win, ws, tab = found
    if SEND_MODE == "allowlist" and sid not in SEND_ALLOW and ws["id"] not in CREATED_WS:
        raise HTTPException(403, "allowlist 모드에서는 이 에이전트가 만든 워크스페이스만 앞으로 가져옵니다")
    with _SEND_LOCK:
        try:
            cmux_client.focus_surface(sid)
        except cmux_client.CmuxError as e:
            _audit(kind="focus", surface=sid, ok=False, error=str(e)[:300])
            raise HTTPException(502, str(e)) from e
        activated = None
        if body.activate:
            activated, _how = cmux_client.activate_app()
        try:
            cmux_client.focus_window(win["id"])
        except cmux_client.CmuxError:
            pass                          # 창 raise 가 실패해도 탭 전환 자체는 유효
        verified = None
        try:
            cur = cmux_client.focused_surface()
            verified = str(cur.get("id") or "").upper() == sid
            if not verified:              # 창 raise 로 탭 선택이 밀렸을 때만 한 번 더
                cmux_client.focus_surface(sid)
                verified = str(cmux_client.focused_surface().get("id") or "").upper() == sid
        except Exception:                 # noqa: BLE001 — 검증 실패는 이동 실패가 아니다
            pass
    _audit(kind="focus", surface=sid, ok=True, verified=verified, window=win["label"], workspace=ws["title"][:80])
    return {**_meta(), "ok": True, "verified": verified, "activated": activated,
            "where": f"{win['label']} › {ws['title']}"}


# ─────────────────────────── 기록 앱 (/api/v1/history/*) ───────────────────────────
#
# 기록 앱(fold-claude-history, 별도 저장소)의 history.py 를 agent/ 에 넣으면 기록 API 가 같이 뜬다.
# 없으면 이어가기에 필요한 /history/live 하나만 여기서 연다(상대 맥이 «그 세션이 살아 있나»를 묻는다).
try:
    import history  # noqa: E402
except ImportError:
    history = None

if history is not None:
    history.set_context(_meta, _audit, _collect)
    app.include_router(history.router)
else:
    @app.get(API + "/history/live")
    def history_live():
        """이 맥의 cmux 에서 지금 살아 있는 claude 탭 — sid → 탭. 이어진 세션이면 옛 id 와 새 id 둘 다로 찾게 한다."""
        out = {}
        for t in _collect().get("tabs") or []:
            row = {k: t.get(k) for k in ("surfaceId", "status", "statusLabel", "windowLabel", "workspaceTitle", "title")}
            for key in (t.get("sessionId"), t.get("liveSessionId")):
                if key:
                    out[str(key).lower()] = row
        return {**_meta(), "sessions": out}


# ─────────────────────── 이어가기 (claude --resume 을 새 워크스페이스에서) ───────────────────────
#
# 폰의 관제실 앱이 맥을 고르면(기본값 없음) 먼저 두 맥 모두에 resume-check 를 묻고, 고른 맥에 resume 을 보낸다.
# resume 은 같은 검사를 **여기서 다시** 한다 — 폰이 본 뒤로 상황이 바뀌었을 수 있다(두 번째 겹).
# 세 번째 겹은 이어서 뜨는 claude 자신의 hand guard(SessionStart).

import resume as resumemod  # noqa: E402


def _resume_state(sid):
    sid = (sid or "").strip().lower()
    try:
        path = resumemod.session_path(sid)            # 형식 검사 + 이 맥에 대화 파일이 있나
    except ValueError as e:
        raise HTTPException(400, str(e)) from e
    except LookupError as e:
        raise HTTPException(404, str(e)) from e
    live = None
    for t in _collect().get("tabs") or []:
        if sid in (str(t.get("sessionId") or "").lower(), str(t.get("liveSessionId") or "").lower()):
            live = {"surfaceId": t.get("surfaceId"), "status": t.get("status"), "statusLabel": t.get("statusLabel"),
                    "workspaceTitle": t.get("workspaceTitle")}
            break
    owner = resumemod.owner_of(sid)
    cwd = resumemod.session_cwd(path)
    cwd_ok = bool(cwd and os.path.isdir(cwd))
    peer_name, peer = (None, None) if live else resumemod.peer_live(MACHINE, sid)
    action, reason = resumemod.verdict(live, owner, cwd, cwd_ok, peer, peer_name)
    names = resumemod.session_names()
    return {"sessionId": sid, "name": names.get(sid), "live": live, "owner": owner, "cwd": cwd, "cwdExists": cwd_ok,
            "peer": {"name": peer_name, "live": peer},
            "action": action, "reason": reason}


@app.get(API + "/history/sessions/{sid}/resume-check")
def resume_check(sid: str):
    return {**_meta(), **_resume_state(sid)}


class ResumeBody(BaseModel):
    force: bool = False               # owner 를 확인할 수 없을 때(action=confirm) 사람이 «그래도» 를 눌렀다
    skipPermissions: bool = False     # claude --dangerously-skip-permissions (웹 뷰어의 Resume Skip)


@app.post(API + "/history/sessions/{sid}/resume")
def resume(sid: str, body: ResumeBody):
    _send_open()
    st = _resume_state(sid)
    sid = st["sessionId"]
    if st["action"] == "open":
        _audit(kind="resume", ok=True, session=sid, already=st["live"]["surfaceId"])
        return {**_meta(), "ok": True, "already": True, **st, "surfaces": [st["live"]["surfaceId"]]}
    if st["action"] == "block" or (st["action"] == "confirm" and not body.force):
        _audit(kind="resume", ok=False, session=sid, action=st["action"], reason=st["reason"])
        raise HTTPException(409, {"reason": st["reason"], "action": st["action"]})
    # `--resume` 긴 형태로 — 관제실(vendor nav.py)은 명령줄의 `--session-id`/`--resume` 만 읽어 탭↔세션을 잇는다.
    # `-r` 로 띄우면 탭의 sessionId 가 비어 «살아 있음»·다른 맥 검사가 이 탭을 못 본다(실측)
    cmd = f"claude {'--dangerously-skip-permissions ' if body.skipPermissions else ''}--resume {sid}"
    name = "↩ " + ((st["name"] or "").strip() or sid[:8])
    with _SEND_LOCK:
        try:
            out, ref = cmux_client.new_workspace(name=name[:60], cwd=st["cwd"], command=cmd, focus=False)
        except cmux_client.CmuxError as e:
            _audit(kind="resume", ok=False, session=sid, error=str(e)[:300])
            raise HTTPException(502, str(e)) from e
        if not ref:
            raise HTTPException(502, f"새 워크스페이스 ref 를 못 받았습니다: {out[:200]}")
        wt = cmux_client.workspace_tree(ref)
    ws = next((x for w in wt.get("windows", []) for x in w.get("workspaces", []) if x.get("ref") == ref), None)
    wid = str((ws or {}).get("id") or "").upper()
    surfaces = [str(s.get("id")).upper() for p in (ws or {}).get("panes", []) for s in p.get("surfaces", [])]
    if wid:
        CREATED_WS.add(wid)
    _audit(kind="resume", ok=True, session=sid, cwd=st["cwd"], command=cmd, workspace=wid, force=body.force)
    return {**_meta(), "ok": True, "already": False, **st, "workspaceId": wid, "surfaces": surfaces, "command": cmd}


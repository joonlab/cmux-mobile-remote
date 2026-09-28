"""이어가기 — 세션을 이 맥의 cmux 새 워크스페이스에서 `claude --resume <sid>` 로 되살리기 전에 무엇을 확인하나.

원칙: **세션은 한 번에 한 맥에서만 진행한다.** 그래서 이어가기는 네 가지를 본다.
  ① 이 맥 cmux 에 이미 살아 있나 — 그러면 새로 띄우지 않고 그 탭을 연다
  ② (선택) 세션 소유 사이드카(`<sid>.hand.json`)의 owner — 다른 맥이 잡고 있고 그 pid 가 살아 있으면 막는다.
     이 맥이 잡고 있는데 cmux 탭엔 없으면(iTerm·다른 창) 역시 막는다 — 한 세션을 두 프로세스가 쓰게 된다
  ③ 세션의 작업 폴더가 이 맥에 있나 — `claude -r` 은 폴더와 무관하게 되살아나지만(2.1.263 실측)
     프로젝트 훅·메모리가 폴더를 따르므로 원래 폴더에서 연다

  ④ 상대 맥 cmux 에 살아 있나 — 상대 에이전트의 /history/live 를 직접 묻는다.
     ★ 사이드카는 파일 동기화로 건너오므로 **늦다**(실측: 한 맥에서 막 이어간 세션을 다른 맥은 몇 초간
     옛 pid(이미 죽음)로 봤다 → 사이드카만 믿으면 통과시킨다). 그래서 상대의 cmux 를 직접 본다

②는 제가 두 맥 사이에서 세션을 넘기려고 따로 쓰는 개인 도구(hand-lib)가 있을 때만 켜진다.
CMR_HAND_LIB 가 가리키는 파일이 없으면 owner 는 «모름»(None)이고 나머지 검사만 한다.
hand-lib 가 갖춰야 할 함수: me() → {"label"} · by_label(label) · pid_alive_local(pid) · pid_alive_remote(host, pid).
"""
import glob
import importlib.util
import json
import os
import re
import unicodedata
import urllib.request

CLAUDE_DIR = os.path.expanduser(os.environ.get("CMR_CLAUDE_DIR", "~/.claude"))
PROJECTS = os.path.join(CLAUDE_DIR, "projects")
HAND_LIB = os.path.expanduser(os.environ.get("CMR_HAND_LIB", os.path.join(CLAUDE_DIR, "scripts", "hand-lib.py")))
NAMES_FILE = os.path.join(CLAUDE_DIR, os.environ.get("CMR_NAMES_FILE", "session-names.json"))
_SID = re.compile(r"^[0-9a-fA-F-]{8,64}$")
CWD_SCAN_LINES = 400                 # cwd 는 첫 몇 줄 안에 나온다 — 거대한 줄을 끝까지 읽지 않게 줄 수·줄 길이 상한
CWD_LINE_MAX = 1 << 20

# 상대 맥 에이전트 — 앱의 MACHINES 와 같은 주소(tailnet 안). 코드에 박지 않고 환경변수로 받는다.
#   CMR_PEER_URL=https://상대-맥-주소:8797   CMR_PEER_NAME=홈맥
# 비우면 상대 맥 검사(④)를 건너뛴다(맥 한 대로 쓸 때).
PEER_URL = os.environ.get("CMR_PEER_URL", "").rstrip("/")
PEER_NAME = os.environ.get("CMR_PEER_NAME", "다른 맥")
PEER_TIMEOUT = 5

_hand = None


def peer_live(machine, sid):
    """상대 맥 cmux 에 이 세션 탭이 있나 → (상대 이름, True·False·None=물어볼 수 없음)."""
    name, url = PEER_NAME, PEER_URL
    if not url:
        return None, None
    try:
        with urllib.request.urlopen(url + "/api/v1/history/live", timeout=PEER_TIMEOUT) as r:
            d = json.load(r)
        return name, sid.lower() in (d.get("sessions") or {})
    except Exception:                                       # noqa: BLE001 — 꺼져 있으면 모른다
        return name, None


def session_path(sid):
    """sid → 이 맥의 대화 파일(~/.claude/projects/*/<sid>.jsonl).
    형식이 틀리면 ValueError, 파일이 없으면 LookupError. projects 밖을 가리키면 거부한다."""
    if not _SID.match(sid or ""):
        raise ValueError("세션 id 형식이 아닙니다")
    hits = glob.glob(os.path.join(PROJECTS, "*", sid + ".jsonl"))
    if not hits:
        raise LookupError(f"이 맥에 대화 파일이 없습니다: {sid}")
    path = os.path.realpath(hits[0])
    if not path.startswith(os.path.realpath(PROJECTS) + os.sep):
        raise LookupError(f"대화 파일이 projects 밖에 있습니다: {sid}")
    return path


def session_names():
    """세션 이름표(있으면) — {sid: 이름}. 없거나 깨졌으면 빈 dict."""
    try:
        with open(NAMES_FILE, encoding="utf-8") as f:
            d = json.load(f)
        return d if isinstance(d, dict) else {}
    except (OSError, ValueError):
        return {}


def hand():
    """hand-lib 모듈(없으면 None — 그땐 owner 를 «모름»으로 둔다)."""
    global _hand
    if _hand is None and os.path.exists(HAND_LIB):
        spec = importlib.util.spec_from_file_location("hand_lib", HAND_LIB)
        if spec is None or spec.loader is None:
            return None
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        _hand = mod
    return _hand


def session_cwd(path):
    """대화 파일의 작업 폴더 — 처음 나오는 `cwd`. 폴더 이름은 NFC 로(Claude Code 가 그렇게 쓴다)."""
    try:
        with open(path, "rb") as f:
            for _ in range(CWD_SCAN_LINES):
                line = f.readline(CWD_LINE_MAX)
                if not line:
                    break
                if b'"cwd"' not in line:
                    continue
                try:
                    cwd = json.loads(line).get("cwd")
                except ValueError:
                    continue
                if cwd:
                    return unicodedata.normalize("NFC", cwd)
    except OSError:
        pass
    return None


def owner_of(sid):
    """hand 사이드카의 owner → {host, pid, mine, alive(True·False·None=확인 불가)} 또는 None(아무도 안 잡음)."""
    h = hand()
    side = glob.glob(os.path.join(CLAUDE_DIR, "projects", "*", sid + ".hand.json"))
    if not side:
        return None
    try:
        with open(side[0], encoding="utf-8") as f:
            ow = (json.load(f) or {}).get("owner")
    except (OSError, ValueError):
        return None
    if not ow or not ow.get("host"):
        return None
    out = {"host": ow["host"], "pid": ow.get("pid"), "since": ow.get("since"), "mine": None, "alive": None}
    if h is None or not ow.get("pid"):
        return out
    me = h.me()["label"]
    out["mine"] = ow["host"] == me
    if out["mine"]:
        out["alive"] = h.pid_alive_local(ow["pid"])
    else:
        host = h.by_label(ow["host"])
        out["alive"] = h.pid_alive_remote(host, ow["pid"]) if host else None
    return out


def verdict(live_here, owner, cwd, cwd_ok, peer=None, peer_name=None):
    """무엇을 할지 — (action, 이유). action: open(이미 여기 cmux 에) · block · confirm(확인 불가 — 명시해야 진행) · resume.
    peer = 상대 맥 cmux 에 살아 있나(True·False·None=모름)."""
    if live_here:
        return "open", "이 맥 cmux 에서 이미 돌고 있습니다 — 그 탭을 엽니다"
    if peer is True:
        return "block", f"{peer_name or '다른 맥'} cmux 에서 돌고 있습니다 — 한 세션은 한 맥에서만"
    if owner and owner.get("alive") is True:
        if owner.get("mine"):
            return "block", f"이 맥에서 cmux 밖(pid {owner.get('pid')})에서 돌고 있습니다 — 거기서 이어가세요"
        return "block", f"{owner['host']} 에서 진행 중입니다(pid {owner.get('pid')}) — 한 세션은 한 맥에서만"
    if not cwd:
        return "block", "대화 파일에서 작업 폴더를 못 찾았습니다"
    if not cwd_ok:
        return "block", f"이 맥에 작업 폴더가 없습니다: {cwd}"
    if owner and owner.get("alive") is None and owner.get("mine") is False:
        return "confirm", f"{owner['host']} 이 잡고 있다고 적혀 있는데 지금 확인할 수 없습니다(ssh 불가)"
    return "resume", "이어갈 수 있습니다"

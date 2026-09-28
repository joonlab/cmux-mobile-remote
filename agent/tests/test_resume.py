"""이어가기 판정(resume.py) — 임시 ~/.claude 로만 돈다. hand-lib 는 가짜로 바꿔 끼운다(ssh 를 부르지 않는다)."""
import json
import os
import sys
import tempfile
import types
import unicodedata

TMP = tempfile.mkdtemp(prefix="cmr-resume-test-")
os.environ["CMR_CLAUDE_DIR"] = TMP
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import resume  # noqa: E402

S = "aaaaaaaa-1111-2222-3333-444444444444"
PROJ = os.path.join(TMP, "projects", "-Users-me")


def fake_hand(me="laptop", local=True, remote=True):
    h = types.SimpleNamespace()
    h.me = lambda: {"label": me}
    h.by_label = lambda label: {"label": label, "alias": label} if label in ("laptop", "homemac") else None
    h.pid_alive_local = lambda pid: local
    h.pid_alive_remote = lambda host, pid: remote
    resume._hand = h


def side(owner):
    os.makedirs(PROJ, exist_ok=True)
    with open(os.path.join(PROJ, S + ".hand.json"), "w", encoding="utf-8") as f:
        json.dump({"owner": owner} if owner else {}, f)


def test_cwd_first_and_nfc():
    os.makedirs(PROJ, exist_ok=True)
    p = os.path.join(PROJ, S + ".jsonl")
    nfd = unicodedata.normalize("NFD", "/Users/me/Desktop/프로젝트")
    with open(p, "w", encoding="utf-8") as f:
        f.write(json.dumps({"type": "summary"}) + "\n")
        f.write("{깨진 줄 \"cwd\"\n")                                  # 해석 안 되는 줄은 건너뛴다
        f.write(json.dumps({"type": "user", "cwd": nfd}, ensure_ascii=False) + "\n")
        f.write(json.dumps({"type": "user", "cwd": "/tmp/later"}) + "\n")
    got = resume.session_cwd(p)
    assert got == unicodedata.normalize("NFC", "/Users/me/Desktop/프로젝트"), got
    assert resume.session_cwd(os.path.join(PROJ, "없음.jsonl")) is None


def test_session_path_guards():
    os.makedirs(PROJ, exist_ok=True)
    open(os.path.join(PROJ, S + ".jsonl"), "a").close()
    assert resume.session_path(S).endswith(S + ".jsonl")
    for bad, exc in (("../etc", ValueError), ("", ValueError), ("dddddddd-1111-2222-3333-444444444444", LookupError)):
        try:
            resume.session_path(bad)
        except exc:
            pass
        else:
            raise AssertionError(bad)


def test_owner_none_when_no_sidecar_or_owner():
    fake_hand()
    for f in glob_side():
        os.remove(f)
    assert resume.owner_of(S) is None
    side(None)
    assert resume.owner_of(S) is None


def glob_side():
    import glob
    return glob.glob(os.path.join(TMP, "projects", "*", S + ".hand.json"))


def test_owner_mine_uses_local_pid():
    fake_hand(me="laptop", local=False, remote=True)
    side({"host": "laptop", "pid": 123})
    o = resume.owner_of(S)
    assert o["mine"] is True and o["alive"] is False


def test_owner_other_uses_remote_pid():
    fake_hand(me="laptop", local=False, remote=None)
    side({"host": "homemac", "pid": 9})
    o = resume.owner_of(S)
    assert o["mine"] is False and o["alive"] is None


def test_verdicts():
    V = resume.verdict
    live = {"surfaceId": "X"}
    assert V(live, {"alive": True, "mine": False, "host": "homemac"}, "/a", True)[0] == "open"
    assert V(None, {"alive": True, "mine": False, "host": "homemac", "pid": 9}, "/a", True)[0] == "block"
    assert V(None, {"alive": True, "mine": True, "host": "laptop", "pid": 9}, "/a", True)[0] == "block"   # cmux 밖에서 도는 중
    assert V(None, {"alive": False, "mine": False, "host": "homemac"}, "/a", True)[0] == "resume"       # 죽은 owner = stale
    assert V(None, {"alive": None, "mine": False, "host": "homemac"}, "/a", True)[0] == "confirm"
    assert V(None, {"alive": None, "mine": True, "host": "laptop"}, "/a", True)[0] == "resume"
    assert V(None, None, None, False)[0] == "block"
    assert V(None, None, "/없는", False)[0] == "block"
    assert V(None, None, "/a", True)[0] == "resume"
    # 상대 맥 cmux 에 살아 있으면 사이드카(동기화가 늦다)가 뭐라 하든 막는다
    assert V(None, {"alive": False, "mine": False, "host": "laptop"}, "/a", True, True, "노트북")[0] == "block"
    assert "노트북" in V(None, None, "/a", True, True, "노트북")[1]
    assert V(None, None, "/a", True, None, "노트북")[0] == "resume"                                     # 상대가 꺼져 있으면 모른다
    assert V({"surfaceId": "X"}, None, "/a", True, True, "노트북")[0] == "open"
    # 폴더가 없어도 다른 맥에서 도는 중이면 «진행 중» 이 먼저 보인다
    assert "진행 중" in V(None, {"alive": True, "mine": False, "host": "homemac", "pid": 9}, None, False)[1]


if __name__ == "__main__":
    n = 0
    for k, f in list(globals().items()):
        if k.startswith("test_") and callable(f):
            f(); n += 1
    print(f"ok {n}")

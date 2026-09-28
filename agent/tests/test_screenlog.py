"""화면 적립(screenlog.py) — 임시 폴더에서만 돈다. cmux 를 부르지 않는다."""
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import screenlog as sl  # noqa: E402

S = "AAAAAAAA-1111-2222-3333-444444444444"
CHROME = ["─" * 20, "❯ ", "─" * 20, "  [Opus] 상태줄 12%"]


def screen(body, spin="✶ Musing… (3s)"):
    return "\n".join(body + [spin, "                                  158464 tokens"] + CHROME)


def body(a, b):
    return [f"줄 {i}" for i in range(a, b)]


def spool():
    return sl.Spool(tempfile.mkdtemp(prefix="cmr-screenlog-"))


def log(sp):
    with open(sp.path(S), encoding="utf-8") as f:
        return f.read().splitlines()


def test_scroll_commits_only_lines_that_left():
    sp = spool()
    sp.feed(S, screen(body(0, 30)))
    assert sp.feed(S, screen(body(5, 35), spin="✶ Musing… (5s)")) == 5
    assert sp.feed(S, screen(body(12, 42))) == 7
    assert log(sp) == body(0, 12)


def test_unchanged_and_bottom_churn_commit_nothing():
    sp = spool()
    sp.feed(S, screen(body(0, 30)))
    assert sp.feed(S, screen(body(0, 30))) == 0
    assert sp.feed(S, screen(body(0, 30), spin="✻ Baking… (9s)")) == 0
    assert not os.path.exists(sp.path(S)) or log(sp) == []


def test_middle_expansion_pushes_top_up():
    # 도구 블록이 가운데에서 3줄 늘면 위 3줄이 밀려 나간다
    sp = spool()
    a = body(0, 30)
    sp.feed(S, screen(a))
    b = a[3:20] + ["  ⎿ 결과1", "  ⎿ 결과2", "  ⎿ 결과3"] + a[20:]
    assert sp.feed(S, screen(b)) == 3
    assert log(sp) == body(0, 3)


def test_jump_larger_than_screen_marks_gap():
    sp = spool()
    sp.feed(S, screen(body(0, 30)))
    sp.feed(S, screen(body(100, 130)))
    got = log(sp)
    # 틈일 때도 입력칸·상태줄은 적지 않는다(본문 + 스피너까지)
    assert got == body(0, 30) + [sl.GAP]


def test_blank_lines_alone_are_not_an_overlap():
    sp = spool()
    sp.feed(S, screen([""] * 10 + body(0, 5)))
    s = sl.stitch(sl._norm(screen([""] * 10 + body(0, 5))), sl._norm(screen([""] * 4 + body(50, 60))))
    assert s is None


def test_restart_marker_only_when_log_exists():
    sp = spool()
    sp.feed(S, screen(body(0, 30)))
    sp.feed(S, screen(body(5, 35)))
    sp2 = sl.Spool(sp.root)                       # 에이전트 재시작
    sp2.feed(S, screen(body(40, 70)))
    assert log(sp2)[-1] == sl.RESTART


def test_read_paging_and_after():
    sp = spool()
    sp._append(S, [f"line {i:04d}" for i in range(1000)])
    tail = sp.read(S, limit=1000)
    assert tail["text"].endswith("line 0999\n") and tail["start"] > 0
    assert tail["text"].startswith("line ")        # 잘린 첫 줄은 버렸다
    older = sp.read(S, before=tail["start"], limit=10 ** 6)
    assert older["start"] == 0 and older["text"].endswith("\n")
    assert (older["text"] + tail["text"]).splitlines() == [f"line {i:04d}" for i in range(1000)]
    sp._append(S, ["new"])
    assert sp.read(S, after=tail["end"])["text"] == "new\n"
    assert sp.read(S, after=10 ** 9)["reset"] is True


def test_closed_tab_flushes_after_grace():
    sp = spool()
    tab = {"surfaceId": S, "surfaceRef": "surface:1", "status": "running"}
    sp.tick([tab], lambda ref: screen(body(0, 30)), now=0)
    sp.tick([], lambda ref: "", now=10)
    assert S in sp.last                            # 잠깐 빠진 것 — 아직 안 닫힌 것으로 본다
    sp.tick([], lambda ref: "", now=45)
    assert S not in sp.last and log(sp)[-1] == CHROME[-1].rstrip()


def test_block_rewrap_is_not_a_gap():
    # 도구가 끝나면 블록 들여쓰기가 바뀌어 줄바꿈이 달라진다(실측 2026-09-25). 그 위는 그대로 이어져야 한다
    sp = spool()
    a = body(0, 20) + ["⏺ Bash(long command that", "  wraps here)", "  ⎿ running"]
    b = body(4, 20) + ["⏺ Bash(long command", "      that wraps here)", "  ⎿ done", "", "⏺ 다음 답"]
    sp.feed(S, screen(a))
    assert sp.feed(S, screen(b)) == 4
    assert log(sp) == body(0, 4)


def test_bad_surface_id_rejected():
    sp = spool()
    try:
        sp.path("../../etc/passwd")
    except ValueError:
        return
    raise AssertionError("path traversal 이 통과했다")

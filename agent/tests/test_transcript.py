"""대화 읽기(transcript.py) — 임시 폴더에서만 돈다. 진짜 ~/.claude 는 읽지도 쓰지도 않는다."""
import json
import os
import sys
import tempfile

TMP = tempfile.mkdtemp(prefix="cmr-transcript-test-")
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def test_lines_backward_matches_split_and_skips_big_lines():
    import random
    import transcript
    rnd = random.Random(7)
    p = os.path.join(TMP, "lb.jsonl")
    lines = [b"x" * rnd.choice([0, 1, 5, 300, 70_000, 150_000, 600_000]) for _ in range(60)]
    with open(p, "wb") as f:
        f.write(b"\n".join(lines) + b"\n")
    size = os.path.getsize(p)
    want, o = [], 0                                            # 정답: 단순 split 의 (시작, 줄) — 빈 줄 제외
    for ln in lines:
        if ln.strip():
            want.append((o, ln))
        o += len(ln) + 1
    want.reverse()
    got = [(off, raw) for off, raw, _ in transcript._lines_backward(p, size)]
    assert got == want                                         # 조각(256KB) 경계를 넘는 줄 포함
    cap = 100_000                                              # 150KB(조각 안)·600KB(조각 넘김) 둘 다 None
    got2 = list(transcript._lines_backward(p, size, line_cap=cap))
    assert [off for off, _, _ in got2] == [off for off, _ in want]
    for (off, raw, n), (_, ln) in zip(got2, want):
        assert (raw is None) == (len(ln) > cap) and n == len(ln)


def test_read_page_big_user_line_becomes_chip():
    import transcript
    p = os.path.join(TMP, "big.jsonl")
    big = {"type": "user", "uuid": "0003cccc-0000", "message": {"role": "user", "content": "y" * 200}}
    with open(p, "w", encoding="utf-8") as f:
        f.write(json.dumps({"type": "user", "uuid": "0001aaaa-0000", "message": {"role": "user", "content": "작은 질문"}}) + "\n")
        f.write(json.dumps({"type": "progress", "data": "z" * 300}) + "\n")
        f.write(json.dumps(big) + "\n")
    old = transcript.LINE_CAP
    transcript.LINE_CAP = 150                                   # 기본 인자는 정의 때 굳으니 호출 경로로 바꾼다
    try:
        items = [i for off, raw, n in transcript._lines_backward(p, os.path.getsize(p), line_cap=150)
                 if raw is None for i in [transcript._big_line_item(open(p, "rb"), off, n)]]
    finally:
        transcript.LINE_CAP = old
    assert len(items) == 2 and items[0]["kind"] == "system" and "사람 메시지" in items[0]["text"]
    assert items[1] is None                                    # progress 는 조용히 버린다


def test_wanted_keeps_assistant_lines_whose_message_key_comes_first():
    import transcript
    asst = b'{"parentUuid":"p","isSidechain":false,"message":{"id":"m","type":"message","role":"assistant","content":[{"type":"text","text":"hi"}]},"type":"assistant","uuid":"u"}'
    user = b'{"parentUuid":"p","isSidechain":false,"type":"user","message":{"role":"user","content":"q"}}'
    prog = b'{"parentUuid":"p","isSidechain":false,"type":"progress","data":{"type":"user"}}'
    side = b'{"parentUuid":"p","isSidechain":true,"type":"user","message":{"role":"user","content":"q"}}'
    assert transcript._wanted(asst) and transcript._wanted(user)
    assert not transcript._wanted(prog) and not transcript._wanted(side)


if __name__ == "__main__":
    n = 0
    for k, f in list(globals().items()):
        if k.startswith("test_") and callable(f):
            f(); n += 1
    print(f"ok {n}")

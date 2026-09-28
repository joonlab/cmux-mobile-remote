"""Claude Code 세션 .jsonl → 폰에서 읽을 채팅 항목.

화면 덤프(read-screen)가 아니라 transcript 가 «이전 메시지를 편하게 읽는» 정본이다.
구조가 있어서 사람 말·Claude 답·도구 호출을 갈라 그릴 수 있다.

실측(2026-09-25, 이 세션 파일): assistant 는 **블록 하나가 한 줄**이다(text · tool_use · thinking
이 각각 다른 줄). tool_result 는 type=user 줄의 content 리스트에 실려 온다. 사람이 친 프롬프트는
content 가 문자열이고 promptSource 가 붙는다(관제실 nav 와 같은 판정).

파일이 수 MB 라 통째로 읽지 않는다. **뒤에서부터** 청크로 읽어 항목을 limit 개 모으고,
더 옛날로 갈 때 쓸 커서(바이트 오프셋)를 돌려준다.
"""
import json
import os
import re
import time
from datetime import datetime

from dash import nav

CHUNK = 256 * 1024
MAX_TEXT = 20_000          # 한 항목의 본문 상한 — 거대한 붙여넣기 하나가 화면을 막지 않게
_CMD_NAME = re.compile(r"<command-name>\s*(.*?)\s*</command-name>", re.S)
_CMD_ARGS = re.compile(r"<command-args>\s*(.*?)\s*</command-args>", re.S)
_TAGS = re.compile(r"<[^>]+>")


# 실측(2026-09-25): 4.0GB 세션(ac89d94b)의 끝 50MB 에 줄이 53개뿐이었다 — 한 줄이 평균 1MB, 대부분 `progress`.
# 40개를 모으려고 수백 MB 를 거슬러 올라가며 거대한 줄을 통째로 모으고 json.loads 하다가 pm2 메모리 상한(512M)에
# 걸려 에이전트가 재시작됐다. 그래서 ① 한 줄이 LINE_CAP 을 넘으면 모으지 않는다(머리만 본다)
# ② 한 페이지가 훑는 **시간**을 SCAN_SEC 로 자른다(못 채우면 거기까지 + 이어서 읽을 커서).
#   바이트(64MB)로 자르면 progress 만 수백 MB 이어지는 구간에서 빈 페이지가 연달아 나왔다 — 큰 줄은 머리만 읽어
#   64MB 를 46ms 에 넘기므로 시간으로 자른다. 메모리는 ① 이 지킨다.
LINE_CAP = 4 * 1024 * 1024
SCAN_SEC = 1.5
HEAD = 4096
_HEAD_TYPE = re.compile(rb'"type"\s*:\s*"([a-z_-]+)"')


def _lines_backward(path, end, line_cap=LINE_CAP):
    """[0, end) 안의 줄을 **뒤에서부터** (시작 오프셋, bytes 또는 None, 길이) 로 낸다. 빈 줄은 건너뛴다.
    line_cap 을 넘는 줄은 내용을 모으지 않고 None 으로 낸다(메모리를 지키려고) — 호출자가 머리만 따로 읽는다."""
    with open(path, "rb") as f:
        pos, tail, tail_len, big, tail_end = end, [], 0, False, end   # tail = 아직 앞이 안 끝난 줄의 조각들(뒤→앞)
        while pos > 0:
            start = max(0, pos - CHUNK)
            f.seek(start)
            chunk = f.read(pos - start)
            pos = start
            idx = len(chunk)
            while True:
                nl = chunk.rfind(b"\n", 0, idx)
                if nl < 0:                              # 이 조각 앞부분은 아직 줄의 중간이다
                    if not big:
                        tail.append(chunk[:idx])
                        tail_len += idx
                        if tail_len > line_cap:
                            big, tail, tail_len = True, [], 0
                    break
                lstart = start + nl + 1
                n = tail_end - lstart
                if big or n > line_cap:                 # 조각 하나 안에 통째로 든 큰 줄도 같은 규칙
                    yield lstart, None, n
                else:
                    tail.append(chunk[nl + 1:idx])
                    line = b"".join(reversed(tail))
                    if line.strip():
                        yield lstart, line, n
                tail, tail_len, big, tail_end = [], 0, False, lstart - 1
                idx = nl
        if big or tail_end > line_cap:
            yield 0, None, tail_end
        elif tail:
            line = b"".join(reversed(tail))
            if line.strip():
                yield 0, line, len(line)


def _wanted(raw):
    """해석할 가치가 있는 줄인가 — 머리 HEAD 바이트만 본다. parse_line 은 user·assistant 만 쓴다.

    머리에서 **처음** 나오는 "type" 값으로 가른다. 실측(2026-09-25, 이 세션 전 줄): user 줄은 `user`,
    assistant 줄은 `message`(키 순서가 message 먼저라 안쪽 type 이 먼저 잡힌다 — 383/384) 또는 `assistant`,
    progress·attachment 등 나머지는 자기 이름이 먼저 나온다. ⚠️ `assistant` 만 받으면 Claude 답이 전부 사라진다."""
    head = raw[:HEAD]
    if b'"isSidechain":true' in head:
        return False
    m = _HEAD_TYPE.search(head)
    return m is None or m.group(1) in (b"user", b"assistant", b"message")


def _big_line_item(f, off, length):
    """모으지 않은 거대한 줄 → 사람·Claude 메시지면 «생략» 칩, 그 밖(progress 등)은 None."""
    f.seek(off)
    head = f.read(HEAD)
    if b'"isSidechain":true' in head:
        return None
    m = _HEAD_TYPE.search(head)
    typ = m.group(1).decode() if m else "?"
    if typ not in ("user", "assistant"):
        return None
    u = re.search(rb'"uuid"\s*:\s*"([0-9a-fA-F-]+)"', head)
    return {"id": u.group(1).decode() if u else f"big-{off}", "ts": None, "kind": "system",
            "text": f"[{'사람' if typ == 'user' else 'Claude'} 메시지 {length / 1048576:.1f}MB — 너무 커서 생략]"}


def _ts(o):
    s = o.get("timestamp")
    if not s:
        return None
    try:
        return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def _clip(t):
    return t if len(t) <= MAX_TEXT else t[:MAX_TEXT] + f"\n… ({len(t) - MAX_TEXT}자 생략)"


def clean_user_text(txt):
    """사람 발화에서 주입된 꼬리(system-reminder·훅 출력·스킬 라우터)만 떼고 **본문은 전부** 남긴다.
    관제실 nav.clean_prompt 는 미리보기용이라 180자로 자르고 공백을 뭉갠다 — 여기선 쓰지 않는다."""
    t = nav._SYSREM.sub("", txt or "")
    t = nav._HOOK_TAIL.sub("", t)
    t = nav._LINE_NOISE.sub("", t)
    return t.strip()


def tool_summary(name, inp):
    """도구 호출 한 줄 요약. 폰에서는 접힌 칩 한 줄로 보인다."""
    inp = inp if isinstance(inp, dict) else {}
    key = {
        "Bash": "command", "Read": "file_path", "Edit": "file_path", "Write": "file_path",
        "NotebookEdit": "notebook_path", "Grep": "pattern", "Glob": "pattern",
        "Agent": "description", "Skill": "skill", "WebSearch": "query", "WebFetch": "url",
        "ToolSearch": "query", "Artifact": "action",
    }.get(name)
    val = inp.get(key) if key else None
    if val is None:
        val = next((v for v in inp.values() if isinstance(v, str) and v.strip()), "")
    val = str(val).strip().splitlines()[0] if str(val).strip() else ""
    return val[:200]


def _result_text(block):
    c = block.get("content")
    if isinstance(c, str):
        return c
    if isinstance(c, list):
        return "\n".join(x.get("text", "") for x in c if isinstance(x, dict) and x.get("type") == "text")
    return ""


def parse_line(o):
    """한 줄 → (화면 항목 목록, {tool_use_id: 결과}). 사이드체인(서브에이전트)은 버린다."""
    items, results = [], {}
    if o.get("isSidechain"):
        return items, results
    typ = o.get("type")
    msg = o.get("message") or {}
    content = msg.get("content")
    base = {"id": o.get("uuid"), "ts": _ts(o)}

    if typ == "user":
        if isinstance(content, str):
            if o.get("isMeta"):
                return items, results
            m = _CMD_NAME.search(content)
            if m:
                a = _CMD_ARGS.search(content)
                items.append({**base, "kind": "command",
                              "text": (m.group(1) + (" " + a.group(1) if a and a.group(1) else "")).strip()})
            elif o.get("promptSource") == "system" or nav._SKIP.search(content):
                t = re.sub(r"\s+", " ", _TAGS.sub(" ", nav._SYSREM.sub(" ", content))).strip()
                if t:
                    items.append({**base, "kind": "system", "text": t[:240]})
            else:
                t = clean_user_text(content)
                if t:
                    items.append({**base, "kind": "user", "text": _clip(t)})
        elif isinstance(content, list):
            texts = []
            for b in content:
                if not isinstance(b, dict):
                    continue
                if b.get("type") == "tool_result":
                    results[b.get("tool_use_id")] = {
                        "isError": bool(b.get("is_error")), "text": _result_text(b)[:4000]}
                elif b.get("type") == "text" and not o.get("isMeta"):
                    texts.append(b.get("text") or "")
                elif b.get("type") == "image" and not o.get("isMeta"):
                    texts.append("[이미지]")
            t = clean_user_text("\n".join(texts))
            if t:
                items.append({**base, "kind": "user", "text": _clip(t)})

    elif typ == "assistant" and isinstance(content, list):
        for b in content:
            if not isinstance(b, dict):
                continue
            bt = b.get("type")
            if bt == "text" and (b.get("text") or "").strip():
                items.append({**base, "kind": "assistant", "text": _clip(b["text"].strip())})
            elif bt == "tool_use":
                name, inp = b.get("name") or "?", b.get("input") or {}
                if name == "AskUserQuestion":
                    items.append({**base, "kind": "question", "toolUseId": b.get("id"),
                                  "questions": inp.get("questions") or []})
                else:
                    items.append({**base, "kind": "tool", "toolUseId": b.get("id"),
                                  "name": name, "summary": tool_summary(name, inp)})
            # thinking 은 싣지 않는다 — 폰 화면의 소음이고, 내용도 대개 비어 온다
    return items, results


def read_page(path, before=None, limit=40):
    """파일 끝(또는 before 오프셋)에서 거꾸로 읽어 항목을 limit 개 이상 모은다.

    반환 items 는 **옛것 → 새것** 순서다. before 가 None 이면 파일 처음까지 다 읽었다는 뜻이다.
    도구 상태(status)는 같은 창 안에 결과가 있을 때만 확정한다. 결과가 이 페이지보다 뒤에 있으면
    (옛 페이지의 끝자락) «모름»(None)으로 둔다 — 추측으로 «완료»를 채우지 않는다.
    """
    size = os.path.getsize(path)
    end = size if before is None else max(0, min(int(before), size))
    rows, results, n, earliest, capped = [], {}, 0, end, False
    t0 = time.monotonic()
    with open(path, "rb") as fh:
        for off, raw, length in _lines_backward(path, end):
            earliest = off
            if raw is None:
                its, res = ([x] if (x := _big_line_item(fh, off, length)) else []), {}
            elif not _wanted(raw):
                continue                # progress·스냅샷·사이드체인 — 화면에 안 쓰는 줄은 해석하지 않는다
            else:
                try:
                    o = json.loads(raw)
                except (json.JSONDecodeError, ValueError):
                    continue            # 쓰는 중인 마지막 줄 등
                its, res = parse_line(o)
            results.update(res)
            if its:
                rows.append(its)
                n += len(its)
                if n >= limit:
                    break
            if time.monotonic() - t0 > SCAN_SEC:
                capped = True           # 못 채웠어도 여기서 끊는다 — before 로 이어 읽는다
                break
    items = [it for group in reversed(rows) for it in group]
    newest_page = before is None
    for it in items:
        if it["kind"] in ("tool", "question"):
            r = results.get(it.get("toolUseId"))
            if r is not None:
                it["status"] = "error" if r["isError"] else "done"
                if it["kind"] == "question":
                    it["answer"] = r["text"][:1000]
            else:
                it["status"] = "pending" if newest_page else None
    return {"items": items, "before": earliest if earliest > 0 else None, "size": size, "scanCapped": capped}

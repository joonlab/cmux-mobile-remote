"""터미널 화면(read-screen) → «지금 사람의 답을 기다리는 프롬프트».

권한 요청은 transcript 로는 못 잡는다. 실측(2026-09-25): 권한을 묻던 것이 **백그라운드 서브에이전트**의
Bash 였고, 그 tool_use 는 `subagents/*.jsonl` 에만 있어 메인 transcript 끝에는 없었다. 그래서 정본은 화면이다.

화면 모양(Claude Code 2.1.28x 실측 — tests/fixtures/):

  권한      ─────── (마지막 가로줄)
            제목 줄 («Create file», «Bash command» …) · 본문(명령·diff·훅 메시지)
            Do you want to …?
            ❯ 1. Yes / 2. … / 3. No
            Esc to cancel · Tab to amend

  질문      ←  ☐ Color  ☐ Fruit  ✔ Submit  →      (질문이 하나·단일 선택이면 « ☐ Pet » 만)
            Which color?
            ❯ 1. Red  (다음 줄 들여쓰기 = 설명)   다중 선택이면 «1. [ ] Apple»
              n. Type something.                    다중 선택 끝엔 번호 없는 «Submit» 줄(마지막이 아닌 질문이면 «Next» — 2026-09-25 실측)
            ───────
              n+1. Chat about this
            Enter to select · … · Esc to cancel

  검토      Review your answers / ● 질문 / → 답 … / ❯ 1. Submit answers / 2. Cancel

sig 는 «같은 프롬프트인가»를 가리는 지문이다. 폰이 본 화면과 보내기 직전 화면의 sig 가 다르면 보내지 않는다.
"""
import hashlib
import re

_RULE = re.compile(r"^\s*[─━]{12,}\s*$")
_DASH = re.compile(r"^\s*╌{12,}\s*$")
_OPT = re.compile(r"^(?P<pre>\s*)(?P<cur>❯)?\s*(?P<n>\d{1,2})\.\s+(?:\[(?P<box>[ ✔✓xX])\]\s*)?(?P<label>.*?)\s*$")
_SUBMIT_ROW = re.compile(r"^\s*(?P<cur>❯)?\s+(?:Submit|Next)\s*$")   # 마지막 아닌 다중 선택 질문은 «Next»
_TABS = re.compile(r"^\s*(?:←\s+)?(?:[☐☒✔]\s*\S.*?\s*){1,}(?:→)?\s*$")
_GUTTER = re.compile(r"^\s*│\s?")
_OTHER = re.compile(r"^Type something\.?$")


def nows(s):
    """공백을 전부 뺀 비교용 문자열. 터미널은 폭에 맞춰 단어 중간에서도 줄을 접는다(«veri/fy»)."""
    return re.sub(r"\s+", "", s or "")


def _sig(*parts):
    return hashlib.sha256("\x1f".join(parts).encode()).hexdigest()[:16]


def _nonblank(lines):
    return [(i, ln) for i, ln in enumerate(lines) if ln.strip()]


def _options(lines, start, stop):
    """[start, stop) 에서 번호 선택지를 모은다. 들여쓴 비번호 줄은 앞 선택지의 설명(또는 접힌 꼬리)."""
    opts, cursor, submit_row = [], None, None
    for i in range(start, stop):
        ln = lines[i]
        if not ln.strip():
            continue
        m = _OPT.match(ln)
        if m:
            o = {"n": int(m["n"]), "label": m["label"], "desc": "",
                 "checked": None if m["box"] is None else m["box"] != " "}
            if m["cur"]:
                cursor = o["n"]
            opts.append(o)
            continue
        s = _SUBMIT_ROW.match(ln)
        if s:
            submit_row = True
            if s["cur"]:
                cursor = "submit"
            continue
        if opts:
            o = opts[-1]
            o["desc"] = (o["desc"] + " " + ln.strip()).strip()
    return opts, cursor, submit_row


def _ungutter(lines):
    """긴 질문은 «│» 세로줄을 달고 접혀 나온다(실측 2026-09-25 폰에서 답한 질문 — 검토 화면에서 멈춤 사고).
    질문·검토 화면에서는 세로줄을 벗기고 읽는다. 선택지 줄에는 세로줄이 없다."""
    return [_GUTTER.sub("", ln) if _GUTTER.match(ln) else ln for ln in lines]


def _parse_question(lines, footer_i):
    lines = _ungutter(lines)
    # 선택지 영역은 footer 위의 마지막 가로줄 «위»에 있다(가로줄 아래는 «Chat about this»)
    rule_below = next((i for i in range(footer_i - 1, -1, -1) if _RULE.match(lines[i])), None)
    tabs_i = next((i for i in range(footer_i - 1, -1, -1) if _TABS.match(lines[i])
                   and ("☐" in lines[i] or "☒" in lines[i])), None)
    if tabs_i is None:
        return None
    end = rule_below if (rule_below is not None and rule_below > tabs_i) else footer_i
    tabline = lines[tabs_i].strip().strip("←→").strip()
    tabs = [{"label": t.strip(), "done": mark == "☒"}
            for mark, t in re.findall(r"([☐☒])\s*([^☐☒✔]+)", tabline)]
    # 질문은 탭 줄 아래부터 첫 선택지 전까지 — 좁은 창에선 여러 줄로 접힌다
    first_opt = next((i for i in range(tabs_i + 1, end) if _OPT.match(lines[i])), None)
    if first_opt is None:
        return None
    question = " ".join(ln.strip() for ln in lines[tabs_i + 1:first_opt] if ln.strip())
    if not question:
        return None
    opts, cursor, submit_row = _options(lines, first_opt, end)
    if not opts:
        return None
    multi = any(o["checked"] is not None for o in opts)
    # «기타» 칸은 AskUserQuestion 이 늘 **가로줄 위 마지막 번호**로 붙인다. 글을 치면 label 이 그 글로
    # 바뀌므로(«Type something.» → «앵무새 한 마리») 이름이 아니라 자리로 찾는다(실측: 이름으로 찾다가
    # 단일 선택에서 기타 칸을 잃고 엉뚱한 글을 보냈다).
    other = opts[-1] if len(opts) >= 2 else None
    labels = [o["label"] for o in opts if o is not other]
    active = next((k for k, t in enumerate(tabs) if not t["done"]), None)
    return {
        "kind": "question", "question": question, "multi": multi,
        "options": opts, "otherN": other["n"] if other else None,
        "otherText": (other["label"] if other and not _OTHER.match(other["label"])
                      and other["label"] != "Type something" else None),
        "cursor": cursor, "hasSubmitRow": bool(submit_row), "tabs": tabs, "activeTab": active,
        "sig": _sig("q", nows(question), *[nows(x) for x in labels]),
    }


def _parse_review(lines, idx):
    lines = _ungutter(lines)
    answers, cur_q = [], None
    i = idx + 1
    while i < len(lines):
        s = lines[i].strip()
        if s.startswith("●"):
            cur_q = s.lstrip("●").strip()
        elif s.startswith("→") and cur_q is not None:
            answers.append({"question": cur_q, "answer": s.lstrip("→").strip()})
            cur_q = None
        elif s.startswith("Ready to submit"):
            break
        elif s and answers and cur_q is None:          # 접힌 답의 꼬리
            answers[-1]["answer"] += " " + s
        elif s and cur_q is not None:                  # 접힌 질문의 꼬리
            cur_q += " " + s
        i += 1
    opts, cursor, _ = _options(lines, i + 1, len(lines))
    if not any(o["label"].startswith("Submit") for o in opts):
        return None
    return {"kind": "review", "answers": answers, "options": opts, "cursor": cursor,
            "sig": _sig("r", *[a["question"] + "=" + a["answer"] for a in answers])}


def _parse_permission(lines, footer_i):
    # footer 위로 올라가며 선택지 묶음 → 그 위 첫 비어있지 않은 줄 = 질문
    j = footer_i - 1
    while j >= 0 and not lines[j].strip():
        j -= 1
    last_opt = j
    while j >= 0 and lines[j].strip() and (_OPT.match(lines[j]) or lines[j].startswith("     ")):
        j -= 1
    first_opt = j + 1
    opts, cursor, _ = _options(lines, first_opt, last_opt + 1)
    if len(opts) < 2 or opts[0]["n"] != 1:
        return None
    while j >= 0 and not lines[j].strip():
        j -= 1
    if j < 0:
        return None
    question = lines[j].strip()
    rule = next((i for i in range(j - 1, -1, -1) if _RULE.match(lines[i])), -1)
    block = [ln for ln in lines[rule + 1:j] if not _DASH.match(ln)]
    # 제목 = 가로줄 바로 아래 첫 줄. 화면 위로 잘려 가로줄이 안 보이면(좁은 창) 모른다 → None
    nb = [ln for ln in block if ln.strip()]
    title = nb[0].strip() if (nb and rule >= 0) else None
    body = "\n".join(_GUTTER.sub("", ln).rstrip() for ln in (block[1:] if title else block)).strip("\n")
    return {"kind": "permission", "title": title, "question": question, "body": body[-4000:],
            "truncatedTop": rule < 0, "options": opts, "cursor": cursor,
            "sig": _sig("p", title or "", nows(body), question, *[o["label"] for o in opts])}


def parse(text):
    """화면 원문 → 프롬프트 dict 또는 None(사람을 기다리는 프롬프트 없음)."""
    lines = (text or "").replace("\r", "").split("\n")
    # 화면 맨 아래부터 본다 — 스크롤백 위쪽에 남은 옛 프롬프트 흔적을 집지 않게.
    for i in range(len(lines) - 1, -1, -1):
        s = lines[i].strip()
        if not s:
            continue
        if s.startswith("Enter to select"):
            return _parse_question(lines, i)
        if s.startswith("Esc to cancel"):
            return _parse_permission(lines, i)
        if s.startswith("Review your answers"):
            return _parse_review(lines, i)
        if s.startswith(("❯ 1. Submit answers", "2. Cancel")) or s.startswith("Ready to submit"):
            continue
        if _OPT.match(lines[i]):
            continue
        # 검토 화면은 footer 가 없다 — 위로 조금 더 올라가며 «Review your answers» 를 찾는다
        for k in range(i, max(-1, i - 30), -1):
            if lines[k].strip().startswith("Review your answers"):
                return _parse_review(lines, k)
        return None
    return None

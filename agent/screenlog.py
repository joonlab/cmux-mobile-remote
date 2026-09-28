"""화면 적립(스풀) — claude 탭의 화면을 주기적으로 읽어 «위로 밀려난 줄»을 파일에 쌓는다.

왜 필요한가(실측 2026-09-25): claude 는 alt-screen TUI 라 `cmux read-screen --scrollback` 도 지금 보이는
42~52줄만 준다. 터미널이 과거를 안 들고 있으니 폰의 «화면» 보기는 처음부터 볼 방법이 없었다.
그래서 에이전트가 화면을 계속 찍어 두고, 두 장 사이에 **위로 밀려 나간 줄**만 떼어 적립한다.

이어 붙이기(stitch): 이전 화면 A 와 지금 화면 B 에서, A 를 s 줄 밀었을 때 A[s:] 의 앞부분이 B 의 앞부분과
충분히 겹치는 가장 작은 s 를 찾는다. 그러면 A[:s] 는 화면 밖으로 나간 줄이다 — 다시 바뀌지 않으므로 확정.
화면 아래쪽(입력칸·상태줄·스피너)은 늘 바뀌지만 겹침은 위에서부터 재므로 상관없다.
겹침을 못 찾으면(폴링 사이에 한 화면 넘게 흘렀거나 화면이 통째로 바뀜) A 전체를 적고 **틈 표시**를 남긴다
— 틈을 조용히 메우지 않는다.

권한·질문 답하기(screenprompt)와는 엮지 않는다. 그쪽은 자기 줄 수로 따로 읽는다.
"""
import os
import re
import threading
import time

GAP = "⋯⋯ 여기서 화면이 한 번에 크게 바뀌었습니다 — 사이 내용이 빠졌을 수 있습니다 ⋯⋯"
RESTART = "⋯⋯ 에이전트 재시작 — 그동안 지나간 화면은 없습니다 ⋯⋯"
MAX_BYTES = 4 * 1024 * 1024        # 탭 하나당. 넘으면 앞 절반을 버린다
KEEP_DAYS = 7                      # 이만큼 안 바뀐 파일은 지운다(닫힌 탭)

# 상태별 폴링 간격(초). 도는 탭만 자주 — idle 탭은 화면이 거의 안 움직인다.
INTERVAL = {"running": 2.0, "background": 3.0, "permission": 3.0, "question": 3.0, "waiting": 10.0}
IDLE_INTERVAL = 60.0
VIEWED_INTERVAL = 2.0              # 폰이 지금 보고 있는 탭은 상태와 무관하게 자주
VIEWED_SEC = 30.0
GONE_SEC = 30.0

_SURF = re.compile(r"^[0-9A-Fa-f-]{36}$")


def _norm(text):
    lines = [ln.rstrip() for ln in (text or "").split("\n")]
    while lines and not lines[-1]:
        lines.pop()
    return lines


def stitch(a, b):
    """a→b 사이에 위로 밀려 나간 줄 수 s. 못 찾으면 None.

    모든 s 에 대해 A[s:] 와 B 의 앞부분이 몇 줄 겹치는지 재고, **가장 길게 겹치는** s 를 고른다(같으면 작은 s).
    «처음 맞는 s» 로 고르면 도구가 끝나며 블록이 다시 접힐 때(줄바꿈 위치가 바뀜) 겹침이 짧아 틈으로 떨어졌다(실측).
    겹침은 우연(빈 줄·괄호 한 줄)이 아니어야 한다 — 내용 있는 줄 3개 이상.
    """
    if a == b:
        return 0
    best, best_run = None, 0
    for s in range(len(a)):
        run = 0
        for x, y in zip(a[s:], b):
            if x != y:
                break
            run += 1
        if run > best_run and sum(1 for x in a[s:s + run] if x.strip()) >= 3:
            best, best_run = s, run
    return best


_RULE = re.compile(r"^\s*─{10,}")
_SPIN = re.compile(r"^[^\w\s]\s\S+… \(")          # ✽ Musing… (8m 46s · …)
_TOKENS = re.compile(r"^\s+[\d,.]+k? tokens$")


def body(lines):
    """claude 화면에서 아래 입력칸(─── ❯ ───)과 그 밑 상태줄을 뗀 본문. 틈으로 통째 적을 때만 쓴다."""
    rules = [i for i, ln in enumerate(lines) if _RULE.match(ln)]
    cut = rules[-2] if len(rules) >= 2 and len(lines) - rules[-2] <= 15 else len(lines)
    out = lines[:cut]
    # 입력칸 바로 위의 스피너(«✽ Musing… (8m 46s · ↓ 38k tokens)»)와 토큰 계수 줄도 뗀다 — 매 순간 바뀌는 소음이다
    while out and (not out[-1].strip() or _SPIN.match(out[-1]) or _TOKENS.match(out[-1])):
        out.pop()
    return out


class Spool:
    def __init__(self, root):
        self.root = root
        os.makedirs(root, exist_ok=True)
        self.last = {}             # surfaceId → 직전 화면(정규화된 줄 목록)
        self.next_at = {}          # surfaceId → 다음 폴링 시각
        self.viewed = {}           # surfaceId → 폰이 마지막으로 본 시각
        self.gone = {}             # surfaceId → 목록에서 처음 빠진 시각
        self.lock = threading.Lock()

    def path(self, surface_id):
        sid = (surface_id or "").upper()
        if not _SURF.match(sid):
            raise ValueError(surface_id)
        return os.path.join(self.root, sid + ".log")

    def _append(self, surface_id, lines):
        if not lines:
            return
        p = self.path(surface_id)
        with self.lock, open(p, "a", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
        if os.path.getsize(p) > MAX_BYTES:
            self._trim(p)

    def _trim(self, p):
        with self.lock:
            with open(p, "rb") as f:
                data = f.read()
            cut = data.find(b"\n", len(data) // 2) + 1
            with open(p, "wb") as f:
                f.write(("⋯⋯ 오래된 화면은 용량 때문에 잘랐습니다 ⋯⋯\n").encode() + data[cut:])

    def feed(self, surface_id, text):
        """화면 한 장을 먹인다. 적립한 줄 수를 돌려준다(테스트·로그용)."""
        cur = _norm(text)
        prev = self.last.get(surface_id)
        self.last[surface_id] = cur
        if prev is None:
            # 이 프로세스에서 처음 보는 탭 — 파일이 이미 있으면 재시작 사이 공백을 밝혀 둔다
            if os.path.exists(self.path(surface_id)):
                self._append(surface_id, [RESTART])
            return 0
        s = stitch(prev, cur)
        if s is None:
            kept = body(prev)
            self._append(surface_id, kept + [GAP])
            return len(kept)
        self._append(surface_id, prev[:s])
        return s

    def due(self, tab, now):
        sid = tab["surfaceId"]
        iv = INTERVAL.get(tab.get("status"), IDLE_INTERVAL)
        if now - self.viewed.get(sid, 0) < VIEWED_SEC:
            iv = min(iv, VIEWED_INTERVAL)
        if now >= self.next_at.get(sid, 0):
            self.next_at[sid] = now + iv
            return True
        return False

    def tick(self, tabs, read, now=None):
        now = time.time() if now is None else now
        for t in tabs:
            sid = str(t.get("surfaceId") or "").upper()
            if not _SURF.match(sid) or not self.due({**t, "surfaceId": sid}, now):
                continue
            try:
                text = read(t.get("surfaceRef") or sid)
            except Exception:                                  # noqa: BLE001
                continue                                       # 이번엔 건너뛴다 — 다음 폴링에 이어 붙는다
            self.feed(sid, text)
        live = {str(t.get("surfaceId") or "").upper() for t in tabs}
        for sid in list(self.last):
            if sid in live:
                self.gone.pop(sid, None)
            elif now - self.gone.setdefault(sid, now) >= GONE_SEC:
                # 닫힌 탭 — 마지막 화면까지 적고 잊는다. 수집이 잠깐 빠뜨린 것과 가르려고 GONE_SEC 기다린다
                self._append(sid, self.last.pop(sid))
                self.next_at.pop(sid, None)
                self.gone.pop(sid, None)

    def gc(self, now=None):
        now = time.time() if now is None else now
        for name in os.listdir(self.root):
            p = os.path.join(self.root, name)
            if name.endswith(".log") and now - os.path.getmtime(p) > KEEP_DAYS * 86400:
                os.remove(p)

    def read(self, surface_id, before=None, after=None, limit=256 * 1024):
        """적립 파일을 바이트 커서로 읽는다. 줄 경계에 맞춘다.

        after 가 있으면 그 뒤로 새로 붙은 것(폰의 4초 갱신). 파일이 그보다 작으면 잘린 것 → reset.
        아니면 before(없으면 끝)에서 거꾸로 limit 바이트. start 가 0 이면 처음까지 읽은 것.
        """
        p = self.path(surface_id)
        size = os.path.getsize(p) if os.path.exists(p) else 0
        if after is not None:
            if after > size:
                return {"text": "", "start": 0, "end": size, "reset": True}
            with open(p, "rb") as f:
                f.seek(after)
                data = f.read(size - after)
            return {"text": data.decode("utf-8", "replace"), "start": after, "end": size, "reset": False}
        end = size if before is None else max(0, min(int(before), size))
        start = max(0, end - limit)
        if not size:
            return {"text": "", "start": 0, "end": 0, "reset": False}
        with open(p, "rb") as f:
            f.seek(start)
            data = f.read(end - start)
        if start > 0:                                          # 잘린 첫 줄은 버리고 다음 줄부터
            cut = data.find(b"\n") + 1
            start += cut
            data = data[cut:]
        return {"text": data.decode("utf-8", "replace"), "start": start, "end": end, "reset": False}


def run_forever(spool, tabs_fn, read, stop=None, step=1.0):
    last_gc = 0.0
    while not (stop and stop.is_set()):
        try:
            spool.tick(tabs_fn(), read)
            if time.time() - last_gc > 3600:
                spool.gc()
                last_gc = time.time()
        except Exception:                                      # noqa: BLE001
            pass                                               # 수집 실패 한 번으로 스레드를 죽이지 않는다
        time.sleep(step)

"""vendor/cmux-dash/static/icons.js(lucide) → IconsGen.kt. 대시보드가 아이콘을 바꾸면 다시 돌린다.
실행: python3 android/tools/gen_icons.py
"""
import json
import os
import re

P = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
src = open(P + "/vendor/cmux-dash/static/icons.js", encoding="utf-8").read()


def _jsonish(block):
    """JS 오브젝트 리터럴 → JSON. **줄 통째로 주석인 줄만** 걷어낸다.

    ⚠️ 원천(icons.js)은 사람이 손으로 고치는 표이고, 그 저장소는 «왜 이 그림인가»를
       항목 옆에 적는 문화다. 그런데 여기서 json.loads 를 그대로 쓰던 탓에 주석 한 줄이
       들어오자 생성기가 통째로 죽었다(2026-09-25, workflow 아이콘 추가 때).
       주석을 못 달게 하는 것보다 생성기가 참는 쪽이 맞다.
       path 문자열 안의 `//` 를 건드리지 않도록 **줄 시작이 //인 줄만** 지운다.
    """
    keep = [ln for ln in block.splitlines() if not ln.lstrip().startswith("//")]
    return re.sub(r",(\s*[}\]])", r"\1", "\n".join(keep))   # 매달린 쉼표도 허용


lucide = json.loads(_jsonish(re.search(r"const LUCIDE =\s*(\{.*?\n\});", src, re.S).group(1)))
sym = dict(re.findall(r"'([\w.]+)':'([\w-]+)'", re.search(r"const SYMBOL = \{(.*?)\};", src, re.S).group(1)))


def d_of(tag, a):
    f = float
    if tag == "path":
        return a["d"]
    if tag == "circle":
        cx, cy, r = f(a["cx"]), f(a["cy"]), f(a["r"])
        return f"M{cx-r} {cy}a{r} {r} 0 1 0 {2*r} 0a{r} {r} 0 1 0 {-2*r} 0"
    if tag == "rect":
        x, y, w, h = f(a.get("x", 0)), f(a.get("y", 0)), f(a["width"]), f(a["height"])
        rx = f(a.get("rx", a.get("ry", 0)))
        if not rx:
            return f"M{x} {y}h{w}v{h}h{-w}z"
        return (f"M{x+rx} {y}h{w-2*rx}a{rx} {rx} 0 0 1 {rx} {rx}v{h-2*rx}a{rx} {rx} 0 0 1 {-rx} {rx}"
                f"h{-(w-2*rx)}a{rx} {rx} 0 0 1 {-rx} {-rx}v{-(h-2*rx)}a{rx} {rx} 0 0 1 {rx} {-rx}z")
    if tag == "line":
        return f"M{a['x1']} {a['y1']}L{a['x2']} {a['y2']}"
    if tag == "polyline":
        pts = a["points"].split()
        return "M" + "L".join(pts[i] + " " + pts[i + 1] for i in range(0, len(pts), 2))
    raise ValueError(tag)


lines = []
for name, shapes in lucide.items():
    ds = ", ".join('"' + d_of(t, a).replace('"', '\\"') + '"' for t, a in shapes)
    lines.append(f'    "{name}" to listOf({ds}),')
syml = ", ".join(f'"{k}" to "{v}"' for k, v in sym.items())
nl = "\n"
out = f'''package kr.joonlab.core

// ⚠️ 생성 파일 — 손으로 고치지 말 것. 원천: vendor/cmux-dash/static/icons.js (lucide v1.11.0, ISC).
// 다시 만들기: android/tools/gen_icons.py (대시보드가 아이콘을 바꾸면 다시 돌린다)

/** lucide 아이콘 이름 → SVG path 목록(24×24, stroke 2, round). circle·rect·line·polyline 은 path 로 바꿔 두었다. */
val LUCIDE_PATHS: Map<String, List<String>> = mapOf(
{nl.join(lines)}
)

/** cmux 그룹의 SF Symbol 이름 → lucide 이름(대시보드 icons.js SYMBOL 과 같다). */
val SF_SYMBOL: Map<String, String> = mapOf({syml})
'''
open(P + "/android/core/src/main/java/kr/joonlab/core/IconsGen.kt", "w", encoding="utf-8").write(out)
print(len(lucide), "icons,", len(sym), "symbols")

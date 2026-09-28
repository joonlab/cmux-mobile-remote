"""창 → 그룹 → 워크스페이스 → 탭 전부(셸·브라우저 포함). 폰의 «창별 보기»와 워크스페이스 조작의 바탕.

- 구조(창·워크스페이스·탭·순서)는 **라이브 트리**(`cmux tree`)가 정본이다.
- 그룹 소속·그룹 이름·색·앵커, 워크스페이스 작업 폴더는 라이브 트리에 없고 **네이티브 세션 JSON**에만 있다
  (nav.workspace_group_map 과 같은 원천). 그 파일은 cmux 가 주기적으로 저장하므로 방금 만든 워크스페이스는
  잠깐 그룹·폴더가 비어 보일 수 있다 — 추측으로 채우지 않는다.
- cmux 그룹은 «앵커 워크스페이스»(그룹 머리)를 하나 두고 멤버를 묶는다. 앵커는 isAnchor 로 표시한다.
"""
import json
import os

from dash import cmux_client, nav

_NATIVE = {"mtime": None, "ws": {}, "groups": {}}


def _native():
    try:
        mt = os.path.getmtime(nav.NATIVE_JSON)
    except OSError:
        return {}, {}
    if _NATIVE["mtime"] == mt:
        return _NATIVE["ws"], _NATIVE["groups"]
    ws_meta, groups = {}, {}
    try:
        with open(nav.NATIVE_JSON, "rb") as f:
            native = json.load(f)
    except Exception:                                          # noqa: BLE001
        return _NATIVE["ws"], _NATIVE["groups"]               # 쓰는 중이면 직전 것
    for w in native.get("windows", []):
        tm = w.get("tabManager") or {}
        for g in tm.get("workspaceGroups") or []:
            if g.get("id"):
                groups[g["id"].upper()] = {
                    "id": g["id"].upper(), "name": g.get("name") or "(그룹)", "color": g.get("customColor"),
                    "icon": g.get("iconSymbol"), "pinned": bool(g.get("isPinned")),
                    "collapsed": bool(g.get("isCollapsed")),
                    "anchor": str(g.get("anchorWorkspaceId") or "").upper() or None,
                }
        for ws in tm.get("workspaces", []):
            wid = str(ws.get("workspaceId") or "").upper()
            if wid:
                ws_meta[wid] = {"groupId": str(ws.get("groupId") or "").upper() or None,
                                "cwd": ws.get("currentDirectory"), "color": ws.get("customColor")}
    _NATIVE.update(mtime=mt, ws=ws_meta, groups=groups)
    return ws_meta, groups


_CLAUDE_FIELDS = ("status", "statusLabel", "cleanTitle", "sessionId", "lastActivity", "askText", "lastPromptText",
                  "prompt")


def build(claude_tabs):
    """claude_tabs: /sessions 가 내보내는 탭 목록(상태 포함). surfaceId 로 붙인다."""
    by_sid = {str(t.get("surfaceId") or "").upper(): t for t in claude_tabs}
    tree = cmux_client.system_tree()
    ws_meta, groups = _native()
    act = tree.get("active") or {}
    windows = []
    for w in sorted(tree.get("windows", []), key=lambda x: x.get("index", 99)):
        wss, gorder = [], []
        for ws in sorted(w.get("workspaces", []), key=lambda x: x.get("index", 99)):
            wid = str(ws.get("id") or "").upper()
            meta = ws_meta.get(wid, {})
            gid = meta.get("groupId")
            g = groups.get(gid) if gid else None
            if g and gid not in gorder:
                gorder.append(gid)
            tabs = []
            for p in ws.get("panes", []):
                for s in p.get("surfaces", []):
                    sid = str(s.get("id") or "").upper()
                    ct = by_sid.get(sid)
                    tabs.append({
                        "surfaceId": sid, "ref": s.get("ref"), "type": s.get("type"),
                        "title": s.get("title"), "url": s.get("url"),
                        "focused": bool(s.get("focused")), "selected": bool(s.get("selected_in_pane")),
                        "claude": {k: ct.get(k) for k in _CLAUDE_FIELDS} if ct else None,
                    })
            wss.append({
                "id": wid, "ref": ws.get("ref"), "index": ws.get("index"), "title": ws.get("title"),
                "pinned": bool(ws.get("pinned")), "selected": bool(ws.get("selected")),
                "color": meta.get("color"), "cwd": meta.get("cwd"), "groupId": gid if g else None,
                "isAnchor": bool(g and g.get("anchor") == wid), "tabs": tabs,
            })
        windows.append({
            "id": str(w.get("id") or "").upper(), "ref": w.get("ref"), "index": w.get("index"),
            "label": f"창 {int(w.get('index') or 0) + 1}",
            "active": str(w.get("id") or "").upper() == str(act.get("window_id") or "").upper(),
            "groups": [groups[g] for g in gorder], "workspaces": wss,
        })
    return {"windows": windows}


def find_surface(tree_out, surface_id):
    """(창, 워크스페이스, 탭) 또는 None."""
    sid = (surface_id or "").upper()
    for w in tree_out["windows"]:
        for ws in w["workspaces"]:
            for t in ws["tabs"]:
                if t["surfaceId"] == sid:
                    return w, ws, t
    return None


def find_workspace(tree_out, ws_id):
    wid = (ws_id or "").upper()
    for w in tree_out["windows"]:
        for ws in w["workspaces"]:
            if ws["id"] == wid:
                return w, ws
    return None

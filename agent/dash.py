"""관제실(vendor/cmux-dash) 모듈을 들여오는 **단 하나의 입구**.

세션↔탭 조인·상태 판정·소켓 인증은 관제실이 두 달 동안 사고를 밟으며 다듬은 코드다.
여기서 다시 쓰면 같은 사고를 다시 밟는다 → import 해서 그대로 쓴다.
관제실을 고치면 vendor/cmux-dash 에서 `git pull` 하면 이쪽도 같이 좋아진다(README «설치» 참고).

⚠️ 관제실의 config 는 import 시점에 CMUX_DASH_DATA 를 읽는다. 기본값은 관제실 폴더의 data/ 라서
   그대로 두면 비번 스태시(self-heal)가 **vendor/cmux-dash 작업트리 안에** 쓰인다 → 먼저 우리 data/ 로 돌린다.
"""
import os
import sys

AGENT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_DIR = os.path.dirname(AGENT_DIR)
VENDOR = os.path.join(REPO_DIR, "vendor", "cmux-dash")
DATA_DIR = os.environ.get("CMR_DATA", os.path.join(AGENT_DIR, "data"))

if not os.path.isfile(os.path.join(VENDOR, "nav.py")):
    # vendor/cmux-dash 가 비어 있으면 모든 탭이 «없음»으로 보인다 — 조용히 0 을 내지 말고 여기서 죽는다.
    raise ImportError(f"관제실 모듈이 없습니다: {VENDOR} "
                      f"(README «설치»대로 cmux-state-dashboard 를 vendor/cmux-dash 에 clone 하세요)")

os.makedirs(DATA_DIR, exist_ok=True)
os.environ.setdefault("CMUX_DASH_DATA", DATA_DIR)
if VENDOR not in sys.path:
    sys.path.insert(0, VENDOR)

import claude_index  # noqa: E402,F401
import cmux_client  # noqa: E402
import nav  # noqa: E402

__all__ = ["nav", "cmux_client", "claude_index", "VENDOR", "DATA_DIR", "REPO_DIR"]

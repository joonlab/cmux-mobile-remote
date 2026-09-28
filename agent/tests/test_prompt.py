"""화면 파서 회귀 테스트. 표본은 테스트 워크스페이스(테스트 claude haiku, CC 2.1.282)에서 떴다. 공개본은 경로·문구를 가상으로 바꿨다.

실행: agent/.venv/bin/python agent/tests/test_prompt.py
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import screenprompt as prompt  # noqa: E402


def fx(name):
    with open(os.path.join(HERE, "fixtures", name), encoding="utf-8") as f:
        return prompt.parse(f.read())


def test_idle_is_none():
    assert fx("idle_narrow.txt") is None


def test_permission_write():
    p = fx("perm_write.txt")
    assert p["kind"] == "permission"
    assert p["title"] == "Create file"
    assert p["question"] == "Do you want to create hello.txt?"
    assert [o["n"] for o in p["options"]] == [1, 2, 3]
    assert p["options"][2]["label"] == "No"
    assert p["cursor"] == 1


def test_permission_narrow_hook_top_cut():
    p = fx("perm_bash_hook_narrow.txt")
    assert p["kind"] == "permission"
    assert p["truncatedTop"] is True and p["title"] is None      # 가로줄이 화면 밖 — 제목을 지어내지 않는다
    assert [o["label"] for o in p["options"]] == ["Yes", "No"]
    assert "danger-guard" in p["body"] and "│" not in p["body"]


def test_permission_sig_depends_on_body():
    a = fx("perm_bash_hook_narrow.txt")
    with open(os.path.join(HERE, "fixtures", "perm_bash_hook_narrow.txt"), encoding="utf-8") as f:
        b = prompt.parse(f.read().replace("out011142", "out999999"))
    assert a["sig"] != b["sig"]                                  # 같은 «Yes/No» 라도 다른 명령이면 다른 프롬프트


def test_question_first_of_two():
    q = fx("ask_two_questions.txt")
    assert q["kind"] == "question" and q["question"] == "Which color?" and q["multi"] is False
    assert [o["label"] for o in q["options"]] == ["Red", "Green", "Blue", "Type something."]
    assert q["options"][0]["desc"] == "A warm, vibrant color"
    assert q["otherN"] == 4 and q["otherText"] is None
    assert [t["label"] for t in q["tabs"]] == ["Color", "Fruit"]     # «Chat about this» 는 선택지가 아니다


def test_question_multi():
    q = fx("ask_multi_second.txt")
    assert q["multi"] is True and q["hasSubmitRow"] is True
    assert [o["checked"] for o in q["options"]] == [False, False, False, False]
    assert q["otherN"] == 4


def test_question_multi_other_typed_keeps_sig():
    a, b = fx("ask_multi_other_typed.txt"), fx("ask_multi_cursor_submit.txt")
    assert a["otherText"] == "망고" and a["cursor"] == 3
    assert b["cursor"] == "submit"
    assert a["sig"] == b["sig"]                                  # 기타 칸에 친 글은 지문에 넣지 않는다


def test_single_one_question():
    q = fx("ask_single_one.txt")
    assert q["question"] == "Which pet?" and q["otherN"] == 3 and q["hasSubmitRow"] is False


def test_single_other_typed_found_by_position():
    q = fx("ask_single_other_typed.txt")
    assert q["otherN"] == 3 and q["otherText"] == "앵무새 한 마리" and q["cursor"] == 3


def test_review():
    r = fx("ask_review.txt")
    assert r["kind"] == "review"
    assert r["answers"] == [{"question": "Which color?", "answer": "Green"},
                            {"question": "Which fruits?", "answer": "Apple, Cherry"}]
    assert r["options"][0]["label"] == "Submit answers"


def test_review_with_gutter_wrapped_questions():
    # 표본: 실제로 제출이 멈췄던 검토 화면과 같은 모양(세로줄 접힘). 공개본은 문구만 가상으로 바꿨다
    r = fx("ask_review_gutter_wrapped.txt")
    assert r["kind"] == "review" and len(r["answers"]) == 2
    assert r["answers"][0]["question"].startswith("회의 메모에 참석자 연락처도")
    assert r["answers"][0]["question"].endswith("보게 됩니다.")
    assert r["answers"][0]["answer"] == "전부 싣기"
    assert r["answers"][1]["answer"] == "작업 폴더로 옮기고 원본은 휴지통 (Recommended)"


def test_question_with_gutter():
    # 표본은 검토 화면 모양에서 유추했다(실물 미확보) — 세로줄이 붙어도 질문 본문만 남는지
    q = fx("ask_question_gutter_wrapped.txt")
    assert q["question"] == "내보낸 CSV(Downloads)에도 참석자 목록이 그대로 들어 있습니다. 이 파일은 어떻게 할까요?"
    assert q["options"][0]["label"] == "작업 폴더로 옮기고 원본은 휴지통 (Recommended)" and q["otherN"] == 3


if __name__ == "__main__":
    n = 0
    for k, v in list(globals().items()):
        if k.startswith("test_") and callable(v):
            v()
            n += 1
            print("ok", k)
    print(f"{n} passed")


def test_multi_first_of_two_has_next_row():
    # 실측 2026-09-25: 질문 둘 중 첫째가 다중 선택이면 끝 줄이 «Submit» 이 아니라 «Next» 다.
    # «Submit» 만 찾다가 폰 답변이 «다중 선택인데 Submit 줄을 못 찾았습니다»(409)로 멈췄다.
    q = fx("ask_multi_first_of_two_next.txt")
    assert q["multi"] is True and q["hasSubmitRow"] is True
    assert [o["checked"] for o in q["options"]] == [True, True, False, False, False]
    assert q["otherN"] == 5 and q["cursor"] == 1
    assert q["options"][-1]["desc"] == ""                        # «Next» 가 기타 칸 설명으로 새지 않는다

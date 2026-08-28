#!/usr/bin/env python3
"""기능 인수 검증 — 명세 01번 4·5장의 조항을 하나씩 실제로 때려본다.

이슈 #48에는 완료 기준(Definition of Done)이 없다. 범위·계약·자율·판정 절차만 있고
"이걸 다 만들면 끝"이 없다. 그래서 판단 기준을 명세에서 직접 뽑아 여기 옮겼다.

각 검사는 조항 번호를 달고 있고, 실패하면 그 조항이 구현되지 않았거나 틀렸다는 뜻이다.
Testcontainers 테스트(36개)는 클래스 단위로 안을 보지만, 이 스크립트는 **밖에서 HTTP로만**
때린다 — 부하 스크립트가 보는 것과 같은 표면이다.

    python3 load-test/acceptance.py
"""

import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
POSITIONS = ["TOP", "JUNGLE", "MID", "BOTTOM", "SUPPORT"]

results = []
_uid = [10_000]


# ── 도구 ──────────────────────────────────────────────────────────────────
def flush():
    subprocess.run(
        ["docker", "compose", "exec", "-T", "redis", "redis-cli", "FLUSHALL"],
        capture_output=True, check=True,
    )
    _uid[0] += 1000


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        BASE + path, data=data, method=method,
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as res:
            raw = res.read().decode()
            return res.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        return e.code, (json.loads(raw) if raw else None)


def new_user():
    _uid[0] += 1
    return _uid[0]


def create(primary, *, queue="FLEX", size=5, purpose="RANK_UP", voice="POSSIBLE",
           tier=14, lo=8, hi=20, subs=None, minutes=90, user=None):
    """요청 하나. 기본값은 서로 전부 맞물리게 깔아 두고 검사 대상만 바꾼다."""
    body = {
        "userId": user if user is not None else new_user(),
        "queue": queue, "targetSize": size, "purpose": purpose,
        "playMinutes": minutes, "voiceMode": voice,
        "primaryPosition": primary,
        "subPositions": subs if subs is not None else [],
        "tierOrder": tier, "allowedTierMinOrder": lo, "allowedTierMaxOrder": hi,
    }
    status, res = call("POST", "/api/match-requests", body)
    return status, res


def create_ok(*a, **kw):
    status, res = create(*a, **kw)
    assert status == 201, f"요청 생성 실패 {status} {res}"
    return res["requestId"]


def state(request_id):
    _, res = call("GET", f"/api/match-requests/{request_id}")
    return res or {}


def wait_offer(request_id, timeout=4.0):
    """매칭 루프가 200ms마다 도니 잠깐 기다린다."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        s = state(request_id)
        if s.get("state") == "OFFERED":
            return s
        time.sleep(0.1)
    return state(request_id)


def wait_state(request_id, want, timeout=4.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        s = state(request_id)
        if s.get("state") == want:
            return s
        time.sleep(0.1)
    return state(request_id)


def five(**kw):
    """다섯 포지션을 하나씩 맡는 5인. 조건은 전부 통과하도록."""
    return [create_ok(p, **kw) for p in POSITIONS]


def accept_all(offer_id, ids):
    for rid in ids:
        call("POST", f"/api/offers/{offer_id}/accept", {"requestId": rid})


def check(clause, title, passed, detail=""):
    results.append((clause, title, passed, detail))
    mark = "  통과" if passed else "  ✗ 실패"
    print(f"{mark}  [{clause}] {title}")
    if detail and not passed:
        print(f"         {detail}")


# ── 4.1 후보 제외 ──────────────────────────────────────────────────────────
def t_purpose_differs():
    flush()
    ids = [create_ok(POSITIONS[i], purpose=("RANK_UP" if i < 4 else "CASUAL"))
           for i in range(5)]
    time.sleep(1.0)
    offered = [i for i in ids if state(i).get("state") == "OFFERED"]
    check("4.1", "플레이 목적이 다르면 묶이지 않는다",
          len(offered) == 0, f"{len(offered)}명이 제안을 받았다")


def t_tier_one_sided():
    flush()
    # 넷은 티어 14에 8~20 허용. 다섯째는 티어 14인데 14~14만 허용 →
    # 자기는 남을 안 받아주므로 양방향 만족이 깨진다.
    ids = [create_ok(POSITIONS[i], tier=14, lo=8, hi=20) for i in range(4)]
    ids.append(create_ok(POSITIONS[4], tier=20, lo=8, hi=20))  # 넷은 20을 받아줌
    time.sleep(1.0)
    offered_wide = [i for i in ids if state(i).get("state") == "OFFERED"]
    ok_wide = len(offered_wide) == 5

    flush()
    ids2 = [create_ok(POSITIONS[i], tier=14, lo=8, hi=20) for i in range(4)]
    ids2.append(create_ok(POSITIONS[4], tier=20, lo=20, hi=20))  # 20은 14를 거부
    time.sleep(1.0)
    offered_narrow = [i for i in ids2 if state(i).get("state") == "OFFERED"]
    ok_narrow = len(offered_narrow) == 0

    check("4.1", "허용 티어 범위는 양쪽 모두 만족해야 한다",
          ok_wide and ok_narrow,
          f"양방향 만족 시 {len(offered_wide)}/5 제안, 한쪽만 만족 시 {len(offered_narrow)}명 제안(0이어야 함)")


def t_voice_clash():
    flush()
    ids = [create_ok(POSITIONS[i], voice=("REQUIRED" if i == 0 else "NONE" if i == 1 else "POSSIBLE"))
           for i in range(5)]
    time.sleep(1.0)
    offered = [i for i in ids if state(i).get("state") == "OFFERED"]
    check("4.2", "필수와 사용안함은 같은 파티에 들어가지 않는다",
          len(offered) == 0, f"{len(offered)}명이 제안을 받았다")


def t_voice_possible_mixes():
    flush()
    ids = [create_ok(POSITIONS[i], voice=("REQUIRED" if i == 0 else "POSSIBLE"))
           for i in range(5)]
    time.sleep(1.0)
    offered = [i for i in ids if state(i).get("state") == "OFFERED"]
    check("4.2", "가능은 필수와도 섞인다",
          len(offered) == 5, f"{len(offered)}/5만 제안을 받았다")


def t_position_infeasible():
    flush()
    # 셋 다 미드만 가능. 3인 파티조차 포지션을 나눌 수 없다.
    ids = [create_ok("MID", size=3, queue="FLEX") for _ in range(3)]
    time.sleep(1.0)
    offered = [i for i in ids if state(i).get("state") == "OFFERED"]
    check("4.4", "포지션 배정이 불가능한 조합은 파티를 성립시키지 않는다",
          len(offered) == 0, f"{len(offered)}명이 제안을 받았다")


def t_same_user_twice():
    flush()
    u = new_user()
    ids = [create_ok("TOP", size=2, user=u), create_ok("MID", size=2, user=u)]
    time.sleep(1.0)
    offered = [i for i in ids if state(i).get("state") == "OFFERED"]
    check("4.1", "같은 사용자의 두 요청은 서로 묶이지 않는다",
          len(offered) == 0, f"{len(offered)}명이 제안을 받았다")


def t_already_in_party():
    flush()
    ids = five()
    s = wait_offer(ids[0])
    accept_all(s["offerId"], ids)
    wait_state(ids[0], "CONFIRMED")

    # 확정 파티에 속한 사용자(첫 번째)가 새 요청을 넣는다.
    first_user = _uid[0] - 4  # five()가 만든 첫 사용자
    busy_again = create_ok("TOP", user=first_user)
    others = [create_ok(p) for p in POSITIONS[1:]]
    time.sleep(1.5)
    offered = state(busy_again).get("state") == "OFFERED"
    check("4.1", "이미 시간이 겹치는 확정 파티에 속한 사람은 후보에서 빠진다",
          not offered, "파티 중인 사용자가 또 제안을 받았다")


# ── 4.3 결정적 처리 ────────────────────────────────────────────────────────
def t_deterministic():
    runs = []
    for _ in range(3):
        flush()
        ids = []
        for i in range(10):
            ids.append(create_ok(POSITIONS[i % 5], subs=[p for p in POSITIONS if p != POSITIONS[i % 5]]))
        time.sleep(1.5)
        offered = tuple(i - ids[0] for i in ids if state(i).get("state") == "OFFERED")
        runs.append(offered)
    check("4.3", "같은 입력이면 항상 같은 파티가 나온다 (3회 반복)",
          len(set(runs)) == 1, f"실행마다 결과가 달랐다: {runs}")


def t_fcfs_order():
    flush()
    # 10명을 순서대로 넣으면 5인 파티 **두 개**가 나온다.
    # 확인할 것은 "몇 명이 묶였나"가 아니라 **앞 다섯이 한 조, 뒤 다섯이 다른 조**인지다.
    ids = []
    for i in range(10):
        ids.append(create_ok(POSITIONS[i % 5],
                             subs=[p for p in POSITIONS if p != POSITIONS[i % 5]]))
        time.sleep(0.02)
    time.sleep(1.5)
    offers = [state(i).get("offerId") for i in ids]
    first, second = set(offers[:5]), set(offers[5:])
    ok = (len(first) == 1 and len(second) == 1
          and None not in first and None not in second
          and first != second)
    check("4.3", "먼저 신청한 다섯이 한 조로, 다음 다섯이 다른 조로 묶인다",
          ok, f"제안 배정: {offers}")


# ── 4.4 포지션 배정 ────────────────────────────────────────────────────────
def t_five_distinct_positions():
    flush()
    ids = five()
    s = wait_offer(ids[0])
    ok = s.get("state") == "OFFERED"
    if ok:
        accept_all(s["offerId"], ids)
        wait_state(ids[0], "CONFIRMED")
        party = state(ids[0]).get("partyId")
        out = subprocess.run(
            ["docker", "compose", "exec", "-T", "redis", "redis-cli",
             "HGETALL", f"party:{party}:members"],
            capture_output=True, text=True, check=True).stdout.split()
        positions = [v.split("|")[0] for v in out[1::2]]
        ok = len(positions) == 5 and len(set(positions)) == 5
        detail = f"배정된 포지션: {positions}"
    else:
        detail = "제안조차 만들어지지 않았다"
    check("4.4", "5인 파티는 다섯 포지션을 각각 한 명씩 채운다", ok, detail)


# ── 5.1 제안 ───────────────────────────────────────────────────────────────
def t_offer_to_everyone():
    flush()
    ids = five()
    time.sleep(1.0)
    states = [state(i) for i in ids]
    offer_ids = {s.get("offerId") for s in states}
    ok = all(s.get("state") == "OFFERED" for s in states) and len(offer_ids) == 1
    check("5.1", "제안은 참가자 전원에게 같은 제안 id로 나간다", ok,
          f"상태 {[s.get('state') for s in states]}, offerId {offer_ids}")


def t_offer_has_deadline():
    flush()
    ids = five()
    s = wait_offer(ids[0])
    ok = isinstance(s.get("expiresAt"), int) and s["expiresAt"] > int(time.time() * 1000)
    check("5.1", "제안에 남은 수락 시간이 실려 나간다", ok, f"expiresAt={s.get('expiresAt')}")


# ── 5.2 수락·거절 ──────────────────────────────────────────────────────────
def t_accept_idempotent():
    flush()
    ids = five()
    s = wait_offer(ids[0])
    codes, counts = [], []
    for _ in range(8):
        code, res = call("POST", f"/api/offers/{s['offerId']}/accept", {"requestId": ids[0]})
        codes.append(code)
        counts.append((res or {}).get("acceptedCount"))
    still_offered = state(ids[0]).get("state") == "OFFERED"
    ok = set(codes) == {200} and set(counts) == {1} and still_offered
    check("5.2", "중복 수락은 한 번만 처리된다 (8회 클릭)", ok,
          f"응답코드 {set(codes)}, 누적 수락 수 {counts}")


def t_accept_count_progresses():
    flush()
    ids = five()
    s = wait_offer(ids[0])
    seen = []
    for rid in ids[:4]:
        _, res = call("POST", f"/api/offers/{s['offerId']}/accept", {"requestId": rid})
        seen.append(res["acceptedCount"])
    check("5.2", "응답 현황이 숫자로만 나온다 (누가 수락했는지는 안 나온다)",
          seen == [1, 2, 3, 4], f"누적 {seen}")


def t_confirm_needs_everyone():
    flush()
    ids = five()
    s = wait_offer(ids[0])
    accept_all(s["offerId"], ids[:4])
    time.sleep(0.4)
    not_yet = state(ids[0]).get("state") == "OFFERED"
    call("POST", f"/api/offers/{s['offerId']}/accept", {"requestId": ids[4]})
    confirmed = wait_state(ids[0], "CONFIRMED").get("state") == "CONFIRMED"
    check("5.3", "전원이 수락했을 때만 확정된다", not_yet and confirmed,
          f"4명 수락 후 {'대기' if not_yet else '확정됨(틀림)'} / 5명 후 {'확정' if confirmed else '미확정'}")


def t_decline_requeues_survivors():
    flush()
    ids = five()
    s = wait_offer(ids[0])
    call("POST", f"/api/offers/{s['offerId']}/accept", {"requestId": ids[0]})
    call("POST", f"/api/offers/{s['offerId']}/decline",
         {"requestId": ids[2], "keepSearching": True})
    time.sleep(0.4)
    states = [state(i).get("state") for i in ids]
    check("5.3", "제안이 깨지면 정상 이용자는 다시 대기 상태가 된다",
          all(x == "WAITING" for x in states), f"상태 {states}")


def t_decline_stop_searching():
    flush()
    ids = five()
    s = wait_offer(ids[0])
    call("POST", f"/api/offers/{s['offerId']}/decline",
         {"requestId": ids[2], "keepSearching": False})
    time.sleep(0.4)
    quitter = state(ids[2]).get("state")
    others = [state(i).get("state") for i in ids if i != ids[2]]
    check("5.3", "`매칭 종료`를 고른 사람은 재대기하지 않는다",
          quitter == "CANCELLED" and all(x == "WAITING" for x in others),
          f"거절자 {quitter}, 나머지 {others}")


def force_expire(offer_id):
    """만료 시각을 과거로 민다.

    **두 군데를 같이 밀어야 한다.** 만료 시각이 offer:{id} 해시(응답 시점 판정용)와
    offers:pending 정렬셋의 점수(스위퍼용) 두 곳에 있다. 한쪽만 밀면 다른 쪽이 안 움직인다.
    """
    subprocess.run(
        ["docker", "compose", "exec", "-T", "redis", "redis-cli",
         "HSET", f"offer:{offer_id}", "expiresAt", "1"], capture_output=True, check=True)
    subprocess.run(
        ["docker", "compose", "exec", "-T", "redis", "redis-cli",
         "ZADD", "offers:pending", "1", str(offer_id)], capture_output=True, check=True)


def t_timeout_auto_declines():
    flush()
    ids = five()
    s = wait_offer(ids[0])
    force_expire(s["offerId"])
    time.sleep(0.6)
    out = subprocess.run(
        ["docker", "compose", "exec", "-T", "redis", "redis-cli",
         "HGET", f"offer:{s['offerId']}", "status"],
        capture_output=True, text=True, check=True).stdout.strip()
    check("5.2", "수락 제한시간이 지나면 제안이 자동으로 닫힌다",
          out == "EXPIRED", f"제안 상태 {out!r} (EXPIRED여야 함)")


# ── 5.4 실패 조합 억제 ─────────────────────────────────────────────────────
def t_declined_combo_suppressed():
    flush()
    ids = five()
    s = wait_offer(ids[0])
    call("POST", f"/api/offers/{s['offerId']}/decline",
         {"requestId": ids[2], "keepSearching": True})
    time.sleep(1.5)  # 틱이 여러 번 돌 시간
    again = [state(i).get("state") for i in ids]
    check("5.4", "명시적 거절로 깨진 조합은 다시 제안되지 않는다",
          all(x == "WAITING" for x in again), f"상태 {again}")


def t_different_combo_not_suppressed():
    flush()
    ids = five()
    s = wait_offer(ids[0])
    call("POST", f"/api/offers/{s['offerId']}/decline",
         {"requestId": ids[2], "keepSearching": False})
    time.sleep(0.4)
    # 빠진 자리에 다른 사람을 넣으면 구성원이 하나 다른 새 조합이다.
    replacement = create_ok(POSITIONS[2])
    time.sleep(1.5)
    ok = state(replacement).get("state") == "OFFERED"
    check("5.4", "구성원이 한 명만 달라도 다른 조합이라 제안된다", ok,
          f"대체 요청 상태 {state(replacement).get('state')}")


def t_timeout_combo_not_suppressed():
    flush()
    ids = five()
    s = wait_offer(ids[0])
    force_expire(s["offerId"])
    time.sleep(1.5)
    s2 = state(ids[0])
    ok = s2.get("state") == "OFFERED" and s2.get("offerId") != s["offerId"]
    check("5.4", "시간초과로 끝난 조합은 억제하지 않는다 (같은 조합이 다시 제안된다)", ok,
          f"상태 {s2.get('state')}, 이전 제안 {s['offerId']} → 지금 {s2.get('offerId')}")


# ── 3.9 취소 경합 ──────────────────────────────────────────────────────────
def t_cancel_while_waiting():
    flush()
    rid = create_ok("TOP")
    code, res = call("DELETE", f"/api/match-requests/{rid}")
    check("3.9", "대기 중이면 취소가 이긴다",
          code == 200 and res.get("state") == "CANCELLED", f"{code} {res}")


def t_cancel_loses_to_offer():
    flush()
    ids = five()
    wait_offer(ids[0])
    code, res = call("DELETE", f"/api/match-requests/{ids[0]}")
    check("3.9", "제안이 먼저면 취소는 409와 제안 id를 돌려준다",
          code == 409 and res.get("offerId") is not None, f"{code} {res}")


# ── 3.4~3.7 요청 생성 검증 ─────────────────────────────────────────────────
def t_queue_size_rules():
    flush()
    bad, _ = create("TOP", queue="FLEX", size=4)          # 자유랭크 4인 불가
    ok3, _ = create("TOP", queue="FLEX", size=3)
    bad2, _ = create("TOP", queue="SOLO_DUO", size=5, tier=14, lo=12, hi=16)
    check("3.4", "큐별로 허용되지 않는 인원은 거절한다",
          bad == 400 and ok3 == 201 and bad2 == 400,
          f"자유랭크 4인 {bad}, 자유랭크 3인 {ok3}, 솔로듀오 5인 {bad2}")


def t_master_cannot_solo_duo():
    flush()
    master, _ = create("TOP", queue="SOLO_DUO", size=2, tier=29, lo=29, hi=29)
    ok, _ = create("TOP", queue="SOLO_DUO", size=2, tier=14, lo=12, hi=16)
    check("3.6", "마스터 이상은 솔로·듀오 랭크 요청을 만들 수 없다",
          master == 400 and ok == 201, f"마스터 {master}, 일반 {ok}")


def t_tier_range_beyond_game_rule():
    flush()
    # 실버 구간(9~16) 허용 폭은 4. 티어 14가 1~28을 고르면 규칙 초과다.
    too_wide, _ = create("TOP", queue="SOLO_DUO", size=2, tier=14, lo=1, hi=28)
    fine, _ = create("TOP", queue="SOLO_DUO", size=2, tier=14, lo=10, hi=18)
    check("3.5", "게임 규칙이 허용하는 범위를 넘는 티어 범위는 거절한다",
          too_wide == 400 and fine == 201, f"1~28 {too_wide}, 10~18 {fine}")


# ── API 계약 ───────────────────────────────────────────────────────────────
def t_api_contract():
    flush()
    rid = create_ok("TOP")
    code_get, s = call("GET", f"/api/match-requests/{rid}")
    code_me, me = call("GET", f"/api/me/state?userId={_uid[0]}")
    ok = (code_get == 200 and "state" in s
          and code_me == 200 and isinstance(me.get("requests"), list)
          and len(me["requests"]) == 1)
    check("계약", "이슈 #48이 고정한 엔드포인트 형태를 지킨다", ok,
          f"GET {code_get} {s} / me {code_me}")


def t_unknown_request_404():
    code, _ = call("GET", "/api/match-requests/99999999")
    check("계약", "없는 요청은 404", code == 404, f"{code}")


# ── 불변식 ─────────────────────────────────────────────────────────────────
def t_invariants_after_churn():
    flush()
    # 파티를 여러 번 확정시키고 사이사이 거절·취소를 섞는다.
    for round_no in range(6):
        ids = five()
        s = wait_offer(ids[0])
        if s.get("state") != "OFFERED":
            continue
        if round_no % 3 == 2:
            call("POST", f"/api/offers/{s['offerId']}/decline",
                 {"requestId": ids[1], "keepSearching": False})
        else:
            accept_all(s["offerId"], ids)
            wait_state(ids[0], "CONFIRMED", timeout=2.0)

    out = subprocess.run([sys.executable, "load-test/verify-invariants.py"],
                         capture_output=True, text=True)
    body = out.stdout
    ok = "탈락" not in body and "확정 파티 수" in body
    check("4.6", "확정을 반복해도 INV-2·3·4 위반이 없다", ok,
          body.strip().splitlines()[-1] if body else out.stderr)


# ── 실행 ───────────────────────────────────────────────────────────────────
TESTS = [
    ("요청 생성 검증 (명세 3장)", [
        t_queue_size_rules, t_master_cannot_solo_duo, t_tier_range_beyond_game_rule,
        t_cancel_while_waiting, t_cancel_loses_to_offer,
    ]),
    ("후보 제외 (4.1 · 4.2)", [
        t_purpose_differs, t_tier_one_sided, t_voice_clash, t_voice_possible_mixes,
        t_same_user_twice, t_already_in_party,
    ]),
    ("결정적 처리 (4.3)", [t_deterministic, t_fcfs_order]),
    ("포지션 배정 (4.4)", [t_position_infeasible, t_five_distinct_positions]),
    ("제안 (5.1)", [t_offer_to_everyone, t_offer_has_deadline]),
    ("수락·거절 (5.2 · 5.3)", [
        t_accept_idempotent, t_accept_count_progresses, t_confirm_needs_everyone,
        t_decline_requeues_survivors, t_decline_stop_searching, t_timeout_auto_declines,
    ]),
    ("실패 조합 억제 (5.4)", [
        t_declined_combo_suppressed, t_different_combo_not_suppressed,
        t_timeout_combo_not_suppressed,
    ]),
    ("API 계약", [t_api_contract, t_unknown_request_404]),
    ("불변식 (4.6)", [t_invariants_after_churn]),
]


def main():
    print("=" * 72)
    print("기능 인수 검증 — 명세 01번 조항을 HTTP로 직접 때린다")
    print("=" * 72)

    for group, tests in TESTS:
        print(f"\n■ {group}")
        for test in tests:
            try:
                test()
            except Exception as e:
                check("?", f"{test.__name__} 실행 중 예외", False, repr(e))

    passed = sum(1 for *_, ok, _ in results if ok)
    total = len(results)
    print("\n" + "=" * 72)
    print(f"결과   {passed}/{total} 통과")
    if passed < total:
        print("\n실패한 조항")
        for clause, title, ok, detail in results:
            if not ok:
                print(f"  [{clause}] {title}")
                print(f"      {detail}")
    print("=" * 72)
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())

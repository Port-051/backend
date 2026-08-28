#!/usr/bin/env python3
"""불변식 위반 계수. 02 2장 · 이슈 #48 "판정 게이트를 무엇으로 하나".

02 2장은 "위반 검출은 로그가 아니라 데이터베이스를 직접 검사해서 계수한다"고 못박았지만,
이 스파이크는 DB를 쓰지 않는다(이슈 #48). 그래서 확정 결과를 Redis에 남기고 여기서 스캔한다.

**이 대체가 잃는 것을 먼저 적는다.** 02가 검출을 DB에 둔 이유는 부하를 넣는 도구와 위반을
세는 도구를 분리해 "부하 도구가 놓친 위반"이라는 사각을 없애려는 것이었다(04 4.4).
쓰기와 읽기가 같은 Redis면 그 분리가 없다 — **확정을 아예 기록하지 않는 버그가 있으면
위반이 0건으로 나온다.** 그래서 이 스크립트는 위반 계수와 함께 `확정 파티 수`를 반드시 같이
출력하고, 그 수가 부하 스크립트가 센 confirmed와 맞는지 사람이 대조해야 한다.
그 대조 없이 "위반 0건"만 읽으면 02 6.1이 경고한 그대로 — 안전해서가 아니라 잰 적이 없어서
0이 나온 것이다.

읽는 자리
    parties                 파티 id 집합
    party:{id}              queue · targetSize · startAt · endAt · purpose · voiceParty
    party:{id}:members      requestId -> "포지션|userId"
    member:{requestId}      requestId -> partyId
"""

import json
import os
import shlex
import shutil
import subprocess
import sys
from collections import defaultdict

HOST = sys.argv[1] if len(sys.argv) > 1 else "localhost"
PORT = sys.argv[2] if len(sys.argv) > 2 else "6379"

# 호스트에 redis-cli가 없어도 돌아야 한다. Compose로 띄운 redis 컨테이너 안의 것을 쓴다.
# REDIS_CLI 환경변수로 직접 지정할 수도 있다.
if os.environ.get("REDIS_CLI"):
    BASE_CMD = shlex.split(os.environ["REDIS_CLI"])
elif shutil.which("redis-cli"):
    BASE_CMD = ["redis-cli", "-h", HOST, "-p", PORT]
else:
    BASE_CMD = ["docker", "compose", "exec", "-T", "redis", "redis-cli"]


def redis(*args):
    out = subprocess.run(
        [*BASE_CMD, *args], capture_output=True, text=True, check=True,
    )
    return out.stdout.strip()


def redis_lines(*args):
    value = redis(*args)
    return [line for line in value.split("\n") if line]


def main():
    party_ids = redis_lines("SMEMBERS", "parties")
    if not party_ids:
        print("확정된 파티가 없다. 부하를 돌린 뒤에 실행할 것.")
        return 0

    parties = {}
    members_by_party = {}
    for party_id in party_ids:
        fields = redis_lines("HGETALL", f"party:{party_id}")
        parties[party_id] = dict(zip(fields[::2], fields[1::2]))
        raw = redis_lines("HGETALL", f"party:{party_id}:members")
        members = {}
        for request_id, packed in zip(raw[::2], raw[1::2]):
            position, user_id = packed.split("|", 1)
            members[request_id] = {"position": position, "userId": user_id}
        members_by_party[party_id] = members

    violations = {}

    # ── INV-1 정원 초과 ────────────────────────────────────────────────────
    # 02 2장 — 이 위반은 7.4 빈자리 공개 모집 경로에서만 발생한다.
    # 이슈 #48이 6·7장을 범위에서 뺐으므로 **이 스파이크에서는 구조적으로 0건이다.**
    # 0건이 나왔다고 "제어가 잘 됐다"로 읽으면 안 된다. 잴 경로가 없는 것이다.
    inv1 = [
        {"partyId": pid, "targetSize": int(p["targetSize"]), "actual": len(members_by_party[pid])}
        for pid, p in parties.items()
        if len(members_by_party[pid]) > int(p["targetSize"])
    ]
    violations["INV-1 정원 초과"] = inv1

    # ── INV-2 시간이 겹치는 중복 배정 ──────────────────────────────────────
    # 한 사람이 시간이 겹치는 두 파티에 동시에 속하지 않는다.
    intervals = defaultdict(list)
    for pid, members in members_by_party.items():
        start = int(parties[pid]["startAt"])
        end = int(parties[pid]["endAt"])
        for member in members.values():
            intervals[member["userId"]].append((start, end, pid))

    inv2 = []
    for user_id, spans in intervals.items():
        spans.sort()
        for i in range(len(spans) - 1):
            a_start, a_end, a_pid = spans[i]
            b_start, b_end, b_pid = spans[i + 1]
            if b_start < a_end:  # 반열린 구간 [start, end) 로 본다
                inv2.append({"userId": user_id, "partyA": a_pid, "partyB": b_pid})
    violations["INV-2 시간 겹침 중복 배정"] = inv2

    # ── INV-3 요청 단일 배정 ───────────────────────────────────────────────
    # 하나의 요청은 최대 하나의 파티에만 속한다.
    party_of_request = defaultdict(list)
    for pid, members in members_by_party.items():
        for request_id in members:
            party_of_request[request_id].append(pid)

    inv3 = [
        {"requestId": rid, "parties": pids}
        for rid, pids in party_of_request.items()
        if len(pids) > 1
    ]
    violations["INV-3 요청 단일 배정"] = inv3

    # ── INV-4 포지션 중복 ──────────────────────────────────────────────────
    inv4 = []
    for pid, members in members_by_party.items():
        seen = defaultdict(list)
        for request_id, member in members.items():
            seen[member["position"]].append(request_id)
        for position, holders in seen.items():
            if len(holders) > 1:
                inv4.append({"partyId": pid, "position": position, "requestIds": holders})
    violations["INV-4 포지션 중복"] = inv4

    # ── 출력 ───────────────────────────────────────────────────────────────
    total_members = sum(len(m) for m in members_by_party.values())
    print("=" * 68)
    print("판정 게이트 — 불변식 위반 계수 (Redis 스캔)")
    print("=" * 68)
    print(f"확정 파티 수      {len(parties)}")
    print(f"확정 참가자 수    {total_members}")
    print(f"member: 키 수     {len(redis_lines('KEYS', 'member:*'))}")
    print()
    print("⚠️ 위 '확정 파티 수'가 부하 스크립트의 confirmed 카운터와 맞는지 반드시 대조할 것.")
    print("   맞지 않으면 위반 0건은 '안전해서'가 아니라 '잰 적이 없어서'다 (02 6.1).")
    print()

    failed = False
    for name, found in violations.items():
        mark = "통과" if not found else f"탈락 ({len(found)}건)"
        print(f"  {name:28s} {mark}")
        if found:
            failed = True
            for item in found[:5]:
                print(f"      {json.dumps(item, ensure_ascii=False)}")
            if len(found) > 5:
                print(f"      ... 외 {len(found) - 5}건")

    print()
    print("범위 밖 — 이 스파이크에서는 잴 수 없다")
    print("  INV-1 은 01 7.4 빈자리 공개 모집 경로에서만 발생한다. 이슈 #48이 7장을 제외했으므로")
    print("        위 0건은 '제어가 됐다'가 아니라 '경로가 없다'는 뜻이다.")
    print("  INV-5 알림 중복 발송 — 01 8장이 범위 밖이다.")
    print("=" * 68)

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

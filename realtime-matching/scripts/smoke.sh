#!/usr/bin/env bash
# 손으로 한 바퀴 돌려보는 스크립트. 자유 랭크 5인 파티가 성립하고 확정되는 데까지 간다.
#   1) SSE를 연다  2) 요청 5건을 넣는다  3) 제안이 오면 전원 수락한다  4) 확정 이벤트를 본다
set -euo pipefail
BASE="${BASE:-http://localhost:8080}"
POSITIONS=(TOP JUNGLE MID BOTTOM SUPPORT)

echo "== SSE 구독 (user 1) — 백그라운드"
curl -sN "$BASE/api/events?userId=1" &
SSE_PID=$!
trap 'kill $SSE_PID 2>/dev/null || true' EXIT
sleep 1

declare -a REQUEST_IDS
for i in 0 1 2 3 4; do
  BODY=$(cat <<JSON
{"userId": $((i+1)), "queue": "FLEX", "targetSize": 5, "purpose": "RANK_UP",
 "playMinutes": 120, "maxWaitMinutes": 10, "voiceMode": "POSSIBLE",
 "primaryPosition": "${POSITIONS[$i]}", "subPositions": [],
 "tierOrder": 14, "allowedTierMinOrder": 10, "allowedTierMaxOrder": 18}
JSON
)
  RESPONSE=$(curl -s -X POST "$BASE/api/match-requests" -H 'Content-Type: application/json' -d "$BODY")
  echo "요청 생성: $RESPONSE"
  REQUEST_IDS+=("$(echo "$RESPONSE" | sed -n 's/.*"requestId":\([0-9]*\).*/\1/p')")
done

echo "== 매칭 사이클 대기"
sleep 3

OFFER_ID=$(curl -s "$BASE/api/match-requests/${REQUEST_IDS[0]}" | sed -n 's/.*"offerId":\([0-9]*\).*/\1/p')
echo "제안: $OFFER_ID"
[ -n "$OFFER_ID" ] || { echo "제안이 생기지 않았다"; exit 1; }

for ID in "${REQUEST_IDS[@]}"; do
  echo "수락 $ID: $(curl -s -X POST "$BASE/api/offers/$OFFER_ID/accept" \
      -H 'Content-Type: application/json' -d "{\"requestId\": $ID}")"
done

sleep 1
echo "== 확정 후 대기 인원 (0이어야 한다)"
curl -s "$BASE/api/match-requests/waiting?queue=FLEX&targetSize=5"; echo

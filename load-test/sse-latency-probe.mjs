#!/usr/bin/env -S node --experimental-eventsource
// 제안 전달 지연 측정. 02 6.2 · 6.4.
//
// 왜 k6가 아니라 별도 스크립트인가 — stock k6 v2.2.0에 SSE 모듈이 없다.
// 04 4.1이 "0단계에서 먼저 확인할 것"으로 지정한 검사를 돌린 결과이고,
// 04 4.5의 **두 번째 선택지**("시나리오를 쪼갠다")를 따른 것이다.
//
// 재는 것은 02 6.2가 정의한 그대로다.
//
//   제안 전달 지연 = 제안 생성/파티 확정의 서버 시각 ~ 클라이언트 수신 시각
//
// MatchEvent.occurredAt이 발행 측 시각이고 수신 시각은 여기서 찍는다.
//
// **프로브가 직접 요청을 넣는다.** 이벤트는 참가자 본인의 userId로만 나가므로,
// 남이 만든 요청을 구독만 해서는 아무것도 받지 못한다. 그래서 이 프로브의 사용자들도
// 같은 대기 풀에 들어가 k6가 만든 사용자들과 섞여 매칭된다.
//
// ⚠️ 한계 — 발행 시각과 수신 시각이 서로 다른 기계에서 찍힌다. 02 6.2가 "제안 전달 지연은
//    인스턴스 시계 차이의 영향을 받으므로 단일 시계 기준으로 수집한다"고 했는데,
//    이 프로브는 그 조건을 **같은 호스트의 Compose 구성에서만** 만족한다.
//    원격에 붙여 재면 시계 차이가 섞이므로 그 수치는 쓰지 않는다.

const BASE = process.env.BASE_URL || 'http://localhost:8080';
const USERS = parseInt(process.env.PROBE_USERS || '60', 10);
const DURATION_MS = parseInt(process.env.PROBE_DURATION_MS || '30000', 10);
const QUEUE = process.env.QUEUE || 'FLEX';
const TARGET_SIZE = parseInt(process.env.TARGET_SIZE || '5', 10);
const USER_BASE = 900_000;

if (typeof EventSource === 'undefined') {
  console.error('EventSource가 없다. `node --experimental-eventsource sse-latency-probe.mjs`로 실행할 것.');
  process.exit(1);
}

const POSITIONS = ['TOP', 'JUNGLE', 'MID', 'BOTTOM', 'SUPPORT'];
const offerLatencies = [];
const confirmLatencies = [];
const byType = new Map();
const sources = [];
let stopped = false;

function record(type, payload, receivedAt) {
  byType.set(type, (byType.get(type) || 0) + 1);
  const latency = receivedAt - payload.occurredAt;
  if (type === 'OFFER_CREATED') offerLatencies.push(latency);
  if (type === 'PARTY_CONFIRMED') confirmLatencies.push(latency);
}

async function createRequest(userId, index) {
  const primaryPosition = POSITIONS[index % POSITIONS.length];
  const body = {
    userId,
    queue: QUEUE,
    targetSize: TARGET_SIZE,
    purpose: 'RANK_UP',
    playMinutes: 90,
    voiceMode: 'POSSIBLE',
    primaryPosition,
    subPositions: POSITIONS.filter((p) => p !== primaryPosition),
    tierOrder: 14,
    allowedTierMinOrder: 8,
    allowedTierMaxOrder: 20,
  };
  const res = await fetch(`${BASE}/api/match-requests`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) return null;
  return (await res.json()).requestId;
}

async function accept(offerId, requestId) {
  await fetch(`${BASE}/api/offers/${offerId}/accept`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ requestId }),
  }).catch(() => {});
}

for (let i = 0; i < USERS; i++) {
  const userId = USER_BASE + i;
  const source = new EventSource(`${BASE}/api/events?userId=${userId}`);
  sources.push(source);

  for (const type of [
    'OFFER_CREATED', 'OFFER_RESPONSE_UPDATED', 'OFFER_DECLINED',
    'OFFER_EXPIRED', 'PARTY_CONFIRMED', 'REQUEST_CANCELLED', 'MATCH_FAILED',
  ]) {
    source.addEventListener(type, (event) => {
      const receivedAt = Date.now();
      let payload;
      try {
        payload = JSON.parse(event.data);
      } catch {
        return;
      }
      record(type, payload, receivedAt);

      // 제안이 오면 바로 수락한다. 그래야 PARTY_CONFIRMED까지 재진다.
      if (type === 'OFFER_CREATED') accept(payload.offerId, payload.requestId);

      // 제안이 깨지거나 만료되면 새 요청을 넣어 계속 흐르게 한다.
      if (!stopped && (type === 'OFFER_DECLINED' || type === 'OFFER_EXPIRED' || type === 'MATCH_FAILED')) {
        createRequest(userId, i).catch(() => {});
      }
      if (!stopped && type === 'PARTY_CONFIRMED') {
        setTimeout(() => { if (!stopped) createRequest(userId, i).catch(() => {}); }, 300);
      }
    });
  }
}

console.log(`SSE 연결 ${USERS}개 · ${DURATION_MS / 1000}초 수집`);

// 연결이 자리를 잡은 뒤 요청을 넣는다. 먼저 넣으면 첫 제안을 놓친다.
setTimeout(() => {
  for (let i = 0; i < USERS; i++) createRequest(USER_BASE + i, i).catch(() => {});
}, 1000);

setTimeout(() => {
  stopped = true;
  sources.forEach((s) => s.close());

  const summarise = (samples, label) => {
    if (samples.length === 0) return { 측정: label, 표본수: 0 };
    const sorted = [...samples].sort((a, b) => a - b);
    const at = (p) => sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))];
    return {
      측정: label,
      표본수: sorted.length,
      p50_ms: at(0.5),
      p95_ms: at(0.95),
      p99_ms: at(0.99),
      최대_ms: sorted[sorted.length - 1],
    };
  };

  const offer = summarise(offerLatencies, '제안 전달 지연 OFFER_CREATED');
  const confirm = summarise(confirmLatencies, '확정 전달 지연 PARTY_CONFIRMED');

  console.log(JSON.stringify({
    목표: 'p95 1000ms 이하 (02 6.4) — 참가자가 서로 다른 인스턴스에 분산된 상태에서만 유효',
    제안: offer,
    확정: confirm,
    통과: offer.표본수 > 0 && offer.p95_ms <= 1000,
    이벤트별_수신: Object.fromEntries(byType),
    비고: '발행·수신 시각이 같은 호스트에서 찍혔을 때만 유효하다 (02 6.2).',
  }, null, 2));

  process.exit(offer.표본수 > 0 && offer.p95_ms <= 1000 ? 0 : 1);
}, DURATION_MS);

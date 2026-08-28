// 즉시 매칭 부하. 01 4·5장, 02 6장.
//
// ────────────────────────────────────────────────────────────────────────
// ⚠️ SSE는 이 스크립트에 없다. 04 4.1이 "0단계에서 먼저 확인할 것"으로 지정한 검사를 돌린 결과,
//    stock k6 v2.2.0에는 SSE 모듈이 없다 — k6/x/sse · k6/experimental/sse · k6/net/sse 전부 없고,
//    xk6로 직접 빌드해야 한다. 04 4.5가 대비해 둔 분기이고, 그중 **두 번째 선택지**를 골랐다:
//
//      "시나리오를 쪼갠다. 제안 전달 지연은 SSE 수신까지만 재고, 수락은 별도 측정으로 분리한다."
//
//    → 이 스크립트는 REST만 쓴다. 제안 도착은 GET 폴링으로 안다.
//    → 제안 전달 지연(02 6.2)은 sse-latency-probe.mjs가 따로 잰다.
//
//    폴링으로 바뀌면서 잃는 것을 분명히 해둔다. **이 스크립트의 수락 시점은 폴링 주기만큼
//    늦다.** 그래서 여기서 나오는 매칭 성립 시간에는 폴링 지연이 섞여 있고, 그것을
//    "서버가 느리다"로 읽으면 안 된다. 성립률과 정원 초과 여부는 폴링과 무관하므로 그대로 쓴다.
// ────────────────────────────────────────────────────────────────────────
//
// 부하 모델은 open model이다(04 4.3) — constant-arrival-rate.
// ramping-vus로 동시 사용자 수를 고정하면 서버가 느려질수록 부하도 같이 줄어
// p95가 실제보다 좋게 나온다(coordinated omission). 경합 재현이 목적이므로 도착률을 고정한다.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { prng, sampleTier, samplePrimaryPosition, sampleSubPositions, PURPOSES, VOICE_MODES }
  from './seeded-random.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const SEED = parseInt(__ENV.SEED || '20260828', 10);
const DISTRIBUTION = __ENV.TIER_DISTRIBUTION || 'real';   // 'uniform' | 'real'
const QUEUE = __ENV.QUEUE || 'FLEX';
const TARGET_SIZE = parseInt(__ENV.TARGET_SIZE || '5', 10);
const ARRIVAL_RATE = parseInt(__ENV.ARRIVAL_RATE || '50', 10);   // 초당 신청 수
const DURATION = __ENV.DURATION || '60s';
const POLL_INTERVAL = parseFloat(__ENV.POLL_INTERVAL || '0.2');  // 초
const ACCEPT_RATE = parseFloat(__ENV.ACCEPT_RATE || '0.85');     // 제안을 수락할 확률
const MAX_POLLS = parseInt(__ENV.MAX_POLLS || '150', 10);

export const options = {
  scenarios: {
    matching: {
      executor: 'constant-arrival-rate',
      rate: ARRIVAL_RATE,
      timeUnit: '1s',
      duration: DURATION,
      // VU 하나가 신청 → 폴링 → 응답까지 붙들고 있으므로, 도착률 × 평균 체류시간만큼 필요하다.
      // 체류가 최대 대기시간(5분)까지 갈 수 있어 넉넉히 잡는다. 모자라면 k6가
      // "Insufficient VUs"를 내고 **도착률이 실제로 떨어져** open model이 무너진다.
      preAllocatedVUs: Math.max(200, ARRIVAL_RATE * 10),
      maxVUs: Math.max(2000, ARRIVAL_RATE * 60),
    },
  },
  thresholds: {
    // 02 6.4 — 매칭 요청 생성 p95 200ms 이하. 인스턴스 2대, 동시 대기자 200명 구간에서만 유효하다.
    'http_req_duration{endpoint:create}': ['p(95)<200'],
    'create_failed': ['rate<0.01'],
  },
};

const createDuration = new Trend('create_duration', true);
const timeToOffer = new Trend('time_to_offer', true);
const createFailed = new Rate('create_failed');
const createdCount = new Counter('created');
const offered = new Counter('offered');
const confirmed = new Counter('confirmed');
const matchFailed = new Counter('match_failed');
const declined = new Counter('declined');

/** VU와 반복 회차로 씨드를 갈라, 같은 SEED면 같은 부하가 재현되게 한다. */
function randomFor(vu, iter) {
  return prng(SEED + vu * 1_000_003 + iter);
}

export default function () {
  const rand = randomFor(__VU, __ITER);

  const tierOrder = sampleTier(rand, DISTRIBUTION);
  const primaryPosition = samplePrimaryPosition(rand);

  // 허용 티어 범위. 솔로·듀오는 게임 규칙(01 3.5)이 폭을 제한하므로 좁게 잡는다.
  const halfWidth = QUEUE === 'SOLO_DUO' ? 2 : 6;
  const body = {
    userId: __VU * 1_000_000 + __ITER,
    queue: QUEUE,
    targetSize: TARGET_SIZE,
    purpose: PURPOSES[Math.floor(rand() * PURPOSES.length)],
    playMinutes: 60 + Math.floor(rand() * 120),
    voiceMode: VOICE_MODES[Math.floor(rand() * VOICE_MODES.length)],
    primaryPosition,
    subPositions: sampleSubPositions(rand, primaryPosition),
    tierOrder,
    allowedTierMinOrder: Math.max(1, tierOrder - halfWidth),
    allowedTierMaxOrder: Math.min(28, tierOrder + halfWidth),
  };

  const created = http.post(`${BASE}/api/match-requests`, JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json' },
    tags: { endpoint: 'create' },
  });

  createDuration.add(created.timings.duration);
  const ok = check(created, { '201 Created': (r) => r.status === 201 });
  createFailed.add(!ok);
  if (!ok) return;
  createdCount.add(1);

  const requestId = created.json('requestId');
  const startedAt = Date.now();

  // 제안이 올 때까지 폴링한다. SSE가 있었다면 이 자리에서 이벤트를 기다렸을 것이다.
  let handledOffer = false;
  for (let i = 0; i < MAX_POLLS; i++) {
    sleep(POLL_INTERVAL);

    const status = http.get(`${BASE}/api/match-requests/${requestId}`, {
      tags: { endpoint: 'status' },
    });
    if (status.status !== 200) break;

    const state = status.json('state');

    if (state === 'OFFERED' && !handledOffer) {
      handledOffer = true;
      offered.add(1);
      timeToOffer.add(Date.now() - startedAt);

      const offerId = status.json('offerId');
      // 일부는 응답하지 않고 만료시킨다 — 01 5.2의 자동 거절 경로를 태운다.
      const roll = rand();
      if (roll < ACCEPT_RATE) {
        http.post(`${BASE}/api/offers/${offerId}/accept`,
          JSON.stringify({ requestId }),
          { headers: { 'Content-Type': 'application/json' }, tags: { endpoint: 'accept' } });
      } else if (roll < ACCEPT_RATE + (1 - ACCEPT_RATE) / 2) {
        declined.add(1);
        http.post(`${BASE}/api/offers/${offerId}/decline`,
          JSON.stringify({ requestId, keepSearching: true }),
          { headers: { 'Content-Type': 'application/json' }, tags: { endpoint: 'decline' } });
      }
      // 나머지는 아무것도 하지 않는다. 시간초과로 끝난다.
      continue;
    }

    if (state === 'OFFERED') continue;   // 다시 제안됐다. 이번엔 응답하지 않는다
    if (state === 'WAITING') { handledOffer = false; continue; }  // 재대기

    if (state === 'CONFIRMED') { confirmed.add(1); return; }
    if (state === 'FAILED') { matchFailed.add(1); return; }
    if (state === 'CANCELLED') return;
  }
}

export function handleSummary(data) {
  // 02 6.5 — 측정 환경·씨드·설정값을 결과와 함께 기록한다. 수치만 남고 조건이 없으면 재현이 안 된다.
  const conditions = {
    seed: SEED,
    tierDistribution: DISTRIBUTION,
    queue: QUEUE,
    targetSize: TARGET_SIZE,
    arrivalRate: ARRIVAL_RATE,
    duration: DURATION,
    loadModel: 'constant-arrival-rate (open model)',
    offerDeliveryMeasured: false,
    note: 'SSE 미측정 — stock k6에 SSE 모듈이 없다. 04 4.5의 두 번째 선택지를 따라 분리했다.',
  };
  const m = data.metrics;
  const value = (name, field = 'count') =>
    m[name] && m[name].values[field] !== undefined ? m[name].values[field] : 0;

  const created = value('created');
  const lines = [
    '',
    '='.repeat(68),
    '즉시 매칭 부하 결과',
    '='.repeat(68),
    `신청 수                ${created}`,
    `제안 받음              ${value('offered')}`,
    `확정                   ${value('confirmed')}`,
    `매칭 실패(대기 초과)   ${value('match_failed')}`,
    `명시적 거절            ${value('declined')}`,
    '',
    `성립률                 ${created ? ((value('confirmed') / created) * 100).toFixed(1) : 0}%`,
    '',
    `요청 생성 p95          ${value('create_duration', 'p(95)').toFixed(1)} ms   (목표 200ms — 02 6.4)`,
    `요청 생성 평균         ${value('create_duration', 'avg').toFixed(1)} ms`,
    `신청→제안 p95          ${value('time_to_offer', 'p(95)').toFixed(0)} ms   (폴링 지연 포함 — 서버 지연 아님)`,
    '',
    '⚠️ 제안 전달 지연(02 6.2)은 여기 없다. sse-latency-probe.mjs로 따로 잰다.',
    '⚠️ 불변식 위반 계수는 verify-invariants.py로 따로 센다. 이 수치만으로 통과를 판정하지 않는다.',
    '='.repeat(68),
    '',
    '측정 조건',
    JSON.stringify(conditions, null, 2),
    '',
  ];

  return {
    stdout: lines.join('\n'),
    'results/summary.json': JSON.stringify({ conditions, metrics: data.metrics }, null, 2),
  };
}

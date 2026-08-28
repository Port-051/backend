// 매칭 성립률 곡선. 02번 6.3.
//
// > 동시 대기자 10 / 30 / 50 / 100 / 200명 구간에서 성립률과 평균 대기시간을 잰다.
// > 조건을 전부 필수로 처리하는 방식이 실용 수준에 도달하는 지점을 찾고,
// > 조건 완화 도입 근거로 쓴다.
//
// ── 이 스크립트만 closed model을 쓴다 ─────────────────────────────────────
// 04번 4.3이 부하 모델을 open model(constant-arrival-rate)로 고정하면서 예외를 하나 뒀다 —
// "6.3의 성립률 곡선은 '동시 대기자 N명'이 측정 조건 자체이므로 그 구간만 동시 사용자
// 기준으로 잡고, 리포트에 어느 모델로 쟀는지 표기한다."
//
// 그래서 executor가 `constant-vus`다. VU 하나가 요청을 넣고 그 요청이 끝날 때까지
// 붙어 있다가 다시 넣으므로, **VU 수 = 동시 대기자 수**가 된다.
//
// matching-load.js와 목적이 다르다. 저쪽은 "서버가 버티나"(지연·처리량),
// 이쪽은 "조건을 전부 필수로 걸었을 때 파티가 성립하나"(성립률)다.
// ─────────────────────────────────────────────────────────────────────────

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { prng, sampleTier, samplePrimaryPosition, sampleSubPositions, PURPOSES, VOICE_MODES }
  from './seeded-random.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const SEED = parseInt(__ENV.SEED || '20260828', 10);
const DISTRIBUTION = __ENV.TIER_DISTRIBUTION || 'real';
const QUEUE = __ENV.QUEUE || 'FLEX';
const TARGET_SIZE = parseInt(__ENV.TARGET_SIZE || '5', 10);
const WAITERS = parseInt(__ENV.WAITERS || '50', 10);       // ← 동시 대기자 수
const DURATION = __ENV.DURATION || '90s';
const POLL_INTERVAL = parseFloat(__ENV.POLL_INTERVAL || '0.25');
const ACCEPT_RATE = parseFloat(__ENV.ACCEPT_RATE || '0.90');
const MAX_POLLS = parseInt(__ENV.MAX_POLLS || '600', 10);

export const options = {
  scenarios: {
    curve: {
      executor: 'constant-vus',   // ← open model이 아니다. 위 주석 참조
      vus: WAITERS,
      duration: DURATION,
      gracefulStop: '20s',
    },
  },
};

const settled = new Counter('settled');       // 확정·실패로 끝난 요청 수 (성립률의 분모)
const confirmed = new Counter('confirmed');   // 그중 확정된 수 (분자)
const failed = new Counter('match_failed');
const waitToConfirm = new Trend('wait_to_confirm', true);
const waitToFail = new Trend('wait_to_fail', true);

function randomFor(vu, iter) {
  return prng(SEED + vu * 1_000_003 + iter);
}

export default function () {
  const rand = randomFor(__VU, __ITER);

  const tierOrder = sampleTier(rand, DISTRIBUTION);
  const primaryPosition = samplePrimaryPosition(rand);
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
  if (!check(created, { '201': (r) => r.status === 201 })) return;

  const requestId = created.json('requestId');
  const startedAt = Date.now();
  let handled = false;

  // 이 요청이 끝날 때까지 이 VU는 붙어 있는다. 그래야 VU 수 = 동시 대기자 수가 된다.
  for (let i = 0; i < MAX_POLLS; i++) {
    sleep(POLL_INTERVAL);
    const res = http.get(`${BASE}/api/match-requests/${requestId}`, { tags: { endpoint: 'status' } });
    if (res.status !== 200) break;

    const state = res.json('state');

    if (state === 'OFFERED' && !handled) {
      handled = true;
      const offerId = res.json('offerId');
      if (rand() < ACCEPT_RATE) {
        http.post(`${BASE}/api/offers/${offerId}/accept`, JSON.stringify({ requestId }),
          { headers: { 'Content-Type': 'application/json' }, tags: { endpoint: 'accept' } });
      } else {
        http.post(`${BASE}/api/offers/${offerId}/decline`,
          JSON.stringify({ requestId, keepSearching: true }),
          { headers: { 'Content-Type': 'application/json' }, tags: { endpoint: 'decline' } });
      }
      continue;
    }
    if (state === 'WAITING') { handled = false; continue; }
    if (state === 'OFFERED') continue;

    if (state === 'CONFIRMED') {
      settled.add(1); confirmed.add(1); waitToConfirm.add(Date.now() - startedAt); return;
    }
    if (state === 'FAILED') {
      settled.add(1); failed.add(1); waitToFail.add(Date.now() - startedAt); return;
    }
    if (state === 'CANCELLED') { settled.add(1); return; }
  }
}

export function handleSummary(data) {
  const m = data.metrics;
  const v = (name, field = 'count') =>
    m[name] && m[name].values[field] !== undefined ? m[name].values[field] : 0;

  const total = v('settled');
  const ok = v('confirmed');
  const rate = total ? (ok / total) * 100 : 0;

  const conditions = {
    측정: '매칭 성립률 곡선 (02번 6.3)',
    부하모델: 'constant-vus (closed model) — 04번 4.3의 명시된 예외',
    동시대기자: WAITERS,
    큐: QUEUE,
    정원: TARGET_SIZE,
    티어분포: DISTRIBUTION,
    개인수락률: ACCEPT_RATE,
    실행시간: DURATION,
    씨드: SEED,
  };

  const lines = [
    '',
    '='.repeat(64),
    `성립률 곡선 — 동시 대기자 ${WAITERS}명 · 정원 ${TARGET_SIZE}`,
    '='.repeat(64),
    `끝난 요청        ${total}   (확정 ${ok} · 실패 ${v('match_failed')})`,
    `성립률           ${rate.toFixed(1)} %`,
    `확정까지 평균    ${v('wait_to_confirm', 'avg').toFixed(0)} ms`,
    `확정까지 p95     ${v('wait_to_confirm', 'p(95)').toFixed(0)} ms`,
    `요청 생성 p95    ${v('http_req_duration', 'p(95)').toFixed(1)} ms`,
    '='.repeat(64),
    JSON.stringify(conditions, null, 2),
    '',
  ];

  return {
    stdout: lines.join('\n'),
    [`results/curve-${TARGET_SIZE}-${WAITERS}.json`]:
      JSON.stringify({ conditions, 성립률: rate, metrics: data.metrics }, null, 2),
  };
}

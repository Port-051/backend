/**
 * 즉시 매칭 신청 부하. 04-tech-stack 4장.
 *
 * open model이다(4.3). 도착률을 고정하고 서버가 느려지면 대기가 쌓이게 둔다.
 * 동시 사용자 수를 고정하면 서버가 느려질수록 부하도 같이 줄어서 p95가 실제보다 좋게 나온다.
 *
 * 난수는 씨드를 받는다(4.2). k6의 Math.random()은 씨드를 받지 않아 같은 부하가 재현되지
 * 않으므로 직접 만든다.
 */
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const SEED = Number(__ENV.SEED || 20260829);

/** 최종 도착률(건/초). 단계는 이 값의 10 · 30 · 60 · 100%로 오른다. */
const PEAK = Number(__ENV.PEAK || 1000);

/** 한 단계의 길이. */
const STEP = __ENV.STEP || '20s';

/**
 * 사용자 번호의 시작점.
 *
 * 9.2가 같은 사람의 요청을 둘 두지 못하게 하는데, 앞선 실행의 요청이 아직 대기 중이면
 * 같은 번호가 409로 튕긴다. 실행마다 다른 구간을 쓰게 시작점을 옮긴다.
 *
 * 재현성은 SEED가 지킨다. 신청 조건은 전부 SEED에서 나오고 사용자 번호는 조건에
 * 관여하지 않으므로, 시작점이 달라도 같은 부하가 나온다.
 */
const USER_BASE = Number(__ENV.USER_BASE || Date.now() % 1_000_000_000) * 10;

/** 조합의 현재 대기 인원. 루프가 유입을 따라가는지 보는 값이다. */
const waitingSize = new Trend('matching_waiting_size');

/** 신청이 201로 받아들여진 비율. */
const accepted = new Rate('matching_accepted');

export const options = {
  scenarios: {
    // 도착률을 단계로 올리며 어디서 못 따라가는지 본다.
    apply: {
      executor: 'ramping-arrival-rate',
      startRate: Math.max(1, Math.round(PEAK * 0.05)),
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 600,
      stages: [
        { target: Math.max(1, Math.round(PEAK * 0.1)), duration: STEP },
        { target: Math.max(1, Math.round(PEAK * 0.3)), duration: STEP },
        { target: Math.max(1, Math.round(PEAK * 0.6)), duration: STEP },
        { target: PEAK, duration: STEP },
      ],
      exec: 'apply',
    },
    // 대기 인원을 1초마다 들여다본다. 부하를 거의 주지 않는다.
    probe: {
      executor: 'constant-arrival-rate',
      rate: 1,
      timeUnit: '1s',
      duration: __ENV.PROBE_FOR || '80s',
      preAllocatedVUs: 1,
      maxVUs: 2,
      exec: 'probe',
    },
  },
  thresholds: {
    'matching_accepted': ['rate>0.99'],
    'http_req_failed{scenario:apply}': ['rate<0.01'],
    'http_req_duration{scenario:apply}': ['p(95)<500'],
  },
};

/** mulberry32. 씨드를 받는 난수가 필요해서 직접 둔다. */
function rng(seed) {
  let a = seed >>> 0;
  return function () {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const POSITIONS = ['TOP', 'JUNGLE', 'MID', 'BOTTOM', 'SUPPORT'];
const PURPOSES = ['CASUAL', 'RANK_UP', 'LEARNING', 'EXPERT'];

/**
 * 한 사람의 신청 조건을 짓는다.
 *
 * 티어는 균등 분포다(6.2의 두 조건 중 첫째). 실제 티어 분포는 따로 잰다.
 * 허용 범위는 본인 티어를 담아야 하므로 본인을 가운데 두고 넓힌다.
 */
function body(n) {
  const rand = rng(SEED + n);

  const primary = POSITIONS[Math.floor(rand() * POSITIONS.length)];
  const subs = POSITIONS.filter((p) => p !== primary && rand() < 0.5);
  const tier = 1 + Math.floor(rand() * 28);
  const spread = 2 + Math.floor(rand() * 4);

  return {
    // 9.2 — 같은 사람의 요청이 둘 있으면 안 되므로 실행 전체에서 겹치지 않는 번호를 쓴다.
    userId: USER_BASE + n,
    queue: 'SOLO_DUO',
    targetSize: 2,
    purpose: PURPOSES[Math.floor(rand() * PURPOSES.length)],
    playMinutes: 30 + Math.floor(rand() * 120),
    voiceMode: 'POSSIBLE',
    primaryPosition: primary,
    subPositions: subs,
    tierOrder: tier,
    allowedTierMinOrder: Math.max(1, tier - spread),
    allowedTierMaxOrder: Math.min(29, tier + spread),
  };
}

export function apply() {
  const n = exec.scenario.iterationInTest;
  const res = http.post(`${BASE}/api/match-requests`, JSON.stringify(body(n)), {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'POST /api/match-requests' },
  });

  const ok = res.status === 201;
  accepted.add(ok);
  check(res, { '201로 받아들여진다': () => ok });
}

export function probe() {
  const res = http.get(`${BASE}/api/match-requests/waiting?queue=SOLO_DUO&targetSize=2`, {
    tags: { name: 'GET /api/match-requests/waiting' },
  });
  if (res.status === 200) {
    waitingSize.add(res.json('waiting'));
  }
}

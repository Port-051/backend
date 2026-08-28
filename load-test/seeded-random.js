// 씨드를 받는 난수 생성기와 티어 분포 샘플러.
//
// 04 4.2 — k6에는 씨드를 줄 수 있는 난수 생성기가 없다(`Math.random()`은 씨드를 받지 않는다).
// 02 6.3이 "씨드값은 재현성을 보장하지 타당성을 보장하지 않는다. 입력 분포를 함께 고정한다"고
// 했으므로, 씨드와 분포를 둘 다 여기서 고정하고 리포트에 같이 기록한다.

/** mulberry32 — 32비트 상태 PRNG. 구현이 네 줄이고 주기가 부하 규모에 충분하다. */
export function prng(seed) {
  let a = seed >>> 0;
  return function () {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * 티어 분포 두 가지. 02 6.3이 두 조건에서 각각 재라고 했다.
 *
 *   uniform  알고리즘 자체의 성능을 보는 조건
 *   real     하위·중위 티어에 몰린 실제 롤 분포. 성립률의 현실성을 보는 조건
 *
 * tierOrder는 아이언 IV = 1에서 디비전 단위로 1씩. 아이언~다이아가 1~28이다.
 * 마스터 이상(29~31)은 솔로·듀오 랭크 요청을 만들 수 없으므로(01 3.6) 샘플링하지 않는다.
 *
 * ⚠️ real 분포의 비율은 미확인 근사값이다. 실제 시즌 분포로 대조하기 전까지
 *    "실제 분포에서 성립률이 N%"라고 쓰지 않는다.
 */
const DISTRIBUTIONS = {
  uniform: null, // 1~28 균등
  real: [
    // [tierOrder 상한, 누적 확률]
    [4, 0.10],   // 아이언
    [8, 0.28],   // 브론즈
    [12, 0.50],  // 실버
    [16, 0.72],  // 골드
    [20, 0.87],  // 플래티넘
    [24, 0.96],  // 에메랄드
    [28, 1.00],  // 다이아몬드
  ],
};

export function sampleTier(rand, distribution) {
  const table = DISTRIBUTIONS[distribution];
  if (!table) return 1 + Math.floor(rand() * 28);

  const roll = rand();
  for (let i = 0; i < table.length; i++) {
    if (roll <= table[i][1]) {
      const lower = i === 0 ? 1 : table[i - 1][0] + 1;
      const upper = table[i][0];
      return lower + Math.floor(rand() * (upper - lower + 1));
    }
  }
  return 28;
}

/**
 * 포지션 선호. 02 6.3 — "서포터·정글 지원이 적은 실제 경향을 반영한다."
 * 균등하게 두면 5인 파티가 실제보다 훨씬 잘 성립해 성립률 곡선이 낙관적으로 나온다.
 */
const POSITIONS = ['TOP', 'JUNGLE', 'MID', 'BOTTOM', 'SUPPORT'];
const POSITION_WEIGHTS = [0.26, 0.16, 0.30, 0.20, 0.08];

export function samplePrimaryPosition(rand) {
  const roll = rand();
  let cumulative = 0;
  for (let i = 0; i < POSITIONS.length; i++) {
    cumulative += POSITION_WEIGHTS[i];
    if (roll <= cumulative) return POSITIONS[i];
  }
  return POSITIONS[POSITIONS.length - 1];
}

/** 부 포지션. 아무것도 안 고르는 사람이 절반쯤 된다고 두었다 — 이것도 미확인 가정이다. */
export function sampleSubPositions(rand, primary) {
  const count = rand() < 0.5 ? 0 : 1 + Math.floor(rand() * 2);
  const pool = POSITIONS.filter((p) => p !== primary);
  const picked = [];
  for (let i = 0; i < count && pool.length > 0; i++) {
    picked.push(pool.splice(Math.floor(rand() * pool.length), 1)[0]);
  }
  return picked;
}

export const PURPOSES = ['CASUAL', 'RANK_UP', 'LEARNING', 'SERIOUS'];
export const VOICE_MODES = ['REQUIRED', 'POSSIBLE', 'NONE'];

-- 파티를 확정한다. 판정 게이트가 읽을 기록을 남기는 곳이기도 하다.
--
-- KEYS  [1] parties            파티 id 집합 (스캔 진입점)
--       [2] party:{partyId}
--       [3] party:{partyId}:members
--       [4] mq:{queue}:{size}  대기 명단
-- ARGV  [1] partyId
--       [2] queue  [3] targetSize  [4] startAt  [5] endAt
--       [6] purpose  [7] voiceParty
--       [8] 참가자 수 n
--       그다음 n개씩 세 묶음: requestId..., userId..., position...
--
-- 반환   1 = 확정
--        0 = INV-3에 막혔다 — 이미 다른 파티에 배정된 요청이 있다
--       -1 = INV-4에 막혔다 — 파티 안에서 포지션이 겹친다
--       -2 = INV-2에 막혔다 — 시간이 겹치는 확정 파티에 이미 속한 사람이 있다
--
-- 어느 제약에 막혔는지 구분해서 돌려준다. 판정일에 "확정이 막혔다"만 남으면
-- 무엇이 실제로 일하고 있는지 알 수 없다. 셋은 발생 원인이 완전히 다르다 —
-- INV-3은 인스턴스 간 경합, INV-4는 배정 버그, INV-2는 사전 필터가 놓친 것이다.
--
-- 선점(claim)을 여기서 확인하지 않는다. 제안이 만들어지는 순간 참가자는 이미 대기 명단에서
-- 빠지므로 선점은 그 전까지만 필요하고, 수락을 기다리는 20초 사이에 TTL 5초짜리 선점은
-- 진작 만료돼 있다. 02 1.3이 못박은 대로 **락은 최적화이고 정합성은 제약이 보장한다** —
-- 여기서 정합성을 보장하는 것은 아래 member:{requestId} 검사다.
--
-- 이 스크립트가 INV-3(요청 단일 배정)을 강제한다. member:{requestId}가 이미 있으면
-- 그 요청은 다른 파티에 들어간 것이므로 전체를 되돌린다. Postgres의
-- UNIQUE(request_id)가 하던 일을 Redis에서 원자적으로 하는 자리다.
--
-- INV-4(포지션 중복)는 파티당 포지션을 Hash 필드 키로 쓰지 않고 값으로 쓰므로
-- 여기서 직접 막는다 — 같은 포지션이 두 번 들어오면 중단한다.
--
-- INV-2(시간 겹침 중복 배정)도 여기서 막는다. busy:{userId}가 그 사람이 이미 점유한
-- 구간을 들고 있고, 새 파티의 [startAt, endAt)와 겹치면 중단한다.
-- 02 2장이 PostgreSQL의 `EXCLUDE USING gist (user_id WITH =, during WITH &&)`를 둔 자리이며,
-- **사전 검증이 아니라 쓰기 직전의 원자적 검사**여야 한다는 성질이 같다 —
-- 후보를 고를 때 미리 걸러도 그 사이에 다른 파티가 확정되면 누락이 생긴다.
--
-- 대기 명단(ZSET)의 member는 제로패딩된 requestId다. 01 4.3이 요구하는 전순서가
-- (신청 시각, 요청 ID)인데, 점수가 같을 때 Redis는 member를 사전순으로 비교하므로
-- 자릿수를 맞춰야 사전순이 곧 숫자순이 된다. 여기서도 같은 형식으로 지운다.

local partyId = ARGV[1]
local n       = tonumber(ARGV[8])

local base       = 8
local requestIds = {}
local userIds    = {}
local positions  = {}
for i = 1, n do
  requestIds[i] = ARGV[base + i]
  userIds[i]    = ARGV[base + n + i]
  positions[i]  = ARGV[base + 2 * n + i]
end

-- 1) INV-3 — 이미 어느 파티에 배정된 요청이 하나라도 있으면 중단
for i = 1, n do
  if redis.call('EXISTS', 'member:' .. requestIds[i]) == 1 then
    return 0
  end
end

-- 2) INV-4 — 파티 안에서 포지션이 겹치면 중단
local seen = {}
for i = 1, n do
  if seen[positions[i]] then
    return -1
  end
  seen[positions[i]] = true
end

-- 3) INV-2 — 시간이 겹치는 확정 파티에 이미 속해 있으면 중단
local startAt = tonumber(ARGV[4])
local endAt   = tonumber(ARGV[5])
for i = 1, n do
  local busyKey = 'busy:' .. userIds[i]
  -- 새 파티가 시작하기 전에 끝난 구간은 겹칠 수 없다. 여기서 같이 정리한다.
  -- 구간을 반열린 [startAt, endAt)로 보므로 endAt == startAt인 것도 겹치지 않는다 — 포함해 지운다.
  redis.call('ZREMRANGEBYSCORE', busyKey, '-inf', startAt)
  -- 남은 것은 endAt > startAt인 구간뿐이다. 그중 시작이 새 파티의 끝보다 앞이면 겹친다.
  local held = redis.call('ZRANGE', busyKey, 0, -1)
  for j = 1, #held do
    local sep = string.find(held[j], '|')
    local heldStart = tonumber(string.sub(held[j], sep + 1))
    if heldStart < endAt then
      return -2
    end
  end
end

-- 여기서부터는 되돌리지 않는다. 위 둘을 통과했고 Lua는 중간에 끊기지 않는다.
redis.call('HSET', KEYS[2],
  'partyId',    partyId,
  'queue',      ARGV[2],
  'targetSize', ARGV[3],
  'startAt',    ARGV[4],
  'endAt',      ARGV[5],
  'purpose',    ARGV[6],
  'voiceParty', ARGV[7],
  'size',       tostring(n))

for i = 1, n do
  redis.call('HSET', KEYS[3], requestIds[i], positions[i] .. '|' .. userIds[i])
  redis.call('SET',  'member:'   .. requestIds[i], partyId)
  redis.call('SET',  'reqstate:' .. requestIds[i], 'CONFIRMED')
  redis.call('ZREM', KEYS[4], string.format('%019d', tonumber(requestIds[i])))
  redis.call('DEL',  'claim:'    .. requestIds[i])
  redis.call('DEL',  'reqoffer:' .. requestIds[i])
end

for i = 1, n do
  -- 점유 구간을 남긴다. score는 종료 시각이라 지난 구간을 범위로 잘라낼 수 있다.
  redis.call('ZADD', 'busy:' .. userIds[i], endAt, partyId .. '|' .. ARGV[4])
end

redis.call('SADD', KEYS[1], partyId)
return 1

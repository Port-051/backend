-- 제안에 수락·거절을 반영한다. 01 5.2 · 5.3.
--
-- KEYS  [1] offer:{offerId}            [2] offer:{offerId}:responses
-- ARGV  [1] requestId  [2] 'ACCEPTED' 또는 'DECLINED'  [3] 현재 시각(ms)
--
-- 반환  'ALL_ACCEPTED:{n}'  전원 수락 — 확정으로 넘어간다
--       'PENDING:{n}'       아직 기다린다. n은 수락한 수 (익명 현황용)
--       'DECLINED'          이 응답으로 제안이 깨졌다
--       'ALREADY:{status}'  이미 같은 응답이 반영돼 있다 (중복 클릭·재전송)
--       'EXPIRED'           제한시간이 지났다
--       'CLOSED'            이미 끝난 제안이다
--       'NOT_PARTICIPANT'   이 제안의 참가자가 아니다
--
-- 왜 Lua인가. 01 5.2가 "중복 클릭이나 네트워크 재전송에도 같은 요청을 한 번만 처리한다"를,
-- 5.3이 "필요한 인원이 전부 수락했을 때만 확정한다"를 요구한다. 응답 기록과 전원 수락 판정이
-- 갈라져 있으면 마지막 두 명이 동시에 수락할 때 양쪽 다 "내가 마지막"이라고 읽어
-- 확정이 두 번 돈다. 기록과 판정이 한 덩어리여야 한다.
--
-- 만료를 여기서도 보는 이유는 02 3.3이다 — 예약된 만료 작업과 응답 시점의 지연 판정,
-- 두 경로를 모두 두되 상태 전이는 한 번만 일어나야 한다.

local status = redis.call('HGET', KEYS[1], 'status')
if not status then
  return 'CLOSED'
end
if status ~= 'PENDING' then
  return 'CLOSED'
end

local expiresAt = tonumber(redis.call('HGET', KEYS[1], 'expiresAt'))
if expiresAt and tonumber(ARGV[3]) > expiresAt then
  redis.call('HSET', KEYS[1], 'status', 'EXPIRED')
  return 'EXPIRED'
end

local current = redis.call('HGET', KEYS[2], ARGV[1])
if not current then
  return 'NOT_PARTICIPANT'
end
if current ~= 'PENDING' then
  return 'ALREADY:' .. current
end

redis.call('HSET', KEYS[2], ARGV[1], ARGV[2])

if ARGV[2] == 'DECLINED' then
  redis.call('HSET', KEYS[1], 'status', 'DECLINED')
  return 'DECLINED'
end

local responses = redis.call('HGETALL', KEYS[2])
local accepted = 0
local total = 0
for i = 2, #responses, 2 do
  total = total + 1
  if responses[i] == 'ACCEPTED' then
    accepted = accepted + 1
  end
end

if accepted == total then
  redis.call('HSET', KEYS[1], 'status', 'ALL_ACCEPTED')
  return 'ALL_ACCEPTED:' .. accepted
end

return 'PENDING:' .. accepted

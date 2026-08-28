-- 제안을 만료시킨다. 02 3.3 — 예약된 만료 작업 쪽 경로다.
--
-- KEYS  [1] offer:{offerId}
-- ARGV  [1] 현재 시각(ms)
--
-- 반환  1 = 이 호출이 만료시켰다, 0 = 아직 유효하거나 이미 끝났다
--
-- 응답 시점의 지연 판정(respond_offer.lua)과 동시에 돌아도 상태 전이는 한 번만 일어난다.
-- 둘 다 status가 PENDING일 때만 쓰고, Lua 안에서 읽고 쓰기 때문이다.

local status = redis.call('HGET', KEYS[1], 'status')
if status ~= 'PENDING' then
  return 0
end

local expiresAt = tonumber(redis.call('HGET', KEYS[1], 'expiresAt'))
if not expiresAt or tonumber(ARGV[1]) <= expiresAt then
  return 0
end

redis.call('HSET', KEYS[1], 'status', 'EXPIRED')
return 1

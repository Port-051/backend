-- 좌석 선점 — 참가자 전원에게 "배정 중" 포스트잇을 동시에 붙인다.
-- 하나라도 이미 붙어 있으면 붙인 것을 전부 떼고 실패한다. 부분 선점은 남기지 않는다.
--
-- KEYS[1 .. n]        claim:{requestId}
-- KEYS[n+1 .. 2n]     claim:u:{userId}
-- KEYS[2n+1]          mq:{queue}:{targetSize}
-- ARGV[1] offerId, ARGV[2] TTL(초), ARGV[3] n, ARGV[4 .. 3+n] requestId
--
-- 반환: 1 성립 / -i  i번째 요청이 대기 명단에 없다(취소·만료가 이겼다, 01 3.9)
--       -1000-i  i번째 포스트잇이 이미 붙어 있다(다른 제안이 이겼다)

local offerId = ARGV[1]
local ttl = tonumber(ARGV[2])
local n = tonumber(ARGV[3])
local queueKey = KEYS[2 * n + 1]

for i = 1, n do
  if redis.call('ZSCORE', queueKey, ARGV[3 + i]) == false then
    return -i
  end
end

local acquired = {}
for i = 1, 2 * n do
  if redis.call('SET', KEYS[i], offerId, 'NX', 'EX', ttl) then
    acquired[#acquired + 1] = KEYS[i]
  else
    for _, key in ipairs(acquired) do
      redis.call('DEL', key)
    end
    return -1000 - i
  end
end

return 1

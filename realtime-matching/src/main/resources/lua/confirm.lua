-- 파티 확정 — 참가자 전원을 대기 명단에서 지운다.
-- 실제 아키텍처에서는 Core API가 RDB 트랜잭션과 함께 하는 일이다(11.3).
-- 스파이크에서는 Core API가 없으므로 여기서 명단만 정리한다.
--
-- KEYS[1] mq:{queue}:{targetSize}
-- KEYS[2 .. n+1]        req:{requestId}
-- KEYS[n+2 .. 2n+1]     claim:{requestId}
-- KEYS[2n+2 .. 3n+1]    claim:u:{userId}
-- KEYS[3n+2 .. 4n+1]    user:{userId}:req
-- ARGV[1] n, ARGV[2 .. n+1] requestId

local n = tonumber(ARGV[1])
for i = 1, n do
  local requestId = ARGV[1 + i]
  redis.call('ZREM', KEYS[1], requestId)
  redis.call('DEL', KEYS[1 + i])
  redis.call('DEL', KEYS[1 + n + i])
  redis.call('DEL', KEYS[1 + 2 * n + i])
  redis.call('SREM', KEYS[1 + 3 * n + i], requestId)
end
return n

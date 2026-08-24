-- 취소 — 01 3.9. 취소와 제안 생성이 동시에 일어나면 상태 전이에 먼저 성공한 쪽이 이긴다.
-- 이미 포스트잇이 붙어 있으면(제안이 이겼다) 취소는 실패하고, 호출자는 거절 경로를 안내한다.
--
-- KEYS[1] req:{requestId}, KEYS[2] claim:{requestId}, KEYS[3] mq:{...}, KEYS[4] user:{userId}:req
-- ARGV[1] requestId
--
-- 반환: 1 취소 성립 / 0 제안이 이미 이겼다

if redis.call('EXISTS', KEYS[2]) == 1 then
  return 0
end

redis.call('ZREM', KEYS[3], ARGV[1])
redis.call('DEL', KEYS[1])
redis.call('SREM', KEYS[4], ARGV[1])
return 1

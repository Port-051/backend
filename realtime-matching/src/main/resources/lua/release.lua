-- 선점 해제 — 내가 붙인 포스트잇만 뗀다.
-- 값이 내 offerId가 아니면 다른 제안이 이미 다시 붙인 것이므로 건드리지 않는다.
--
-- KEYS[1..] claim 키, ARGV[1] offerId

for i = 1, #KEYS do
  if redis.call('GET', KEYS[i]) == ARGV[1] then
    redis.call('DEL', KEYS[i])
  end
end
return 1

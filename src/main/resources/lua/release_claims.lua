-- 내가 건 선점만 뗀다.
--
-- KEYS  claim:{requestId} ...
-- ARGV  [1] 선점 토큰
--
-- 반환  실제로 뗀 개수
--
-- 토큰을 확인하는 이유는 TTL 만료 때문이다. 내 선점이 만료된 뒤 다른 매처가 같은 요청을
-- 다시 잡았을 수 있는데, 그때 내가 DEL 하면 남의 선점을 뜯는 것이 된다.

local released = 0
for i = 1, #KEYS do
  if redis.call('GET', KEYS[i]) == ARGV[1] then
    redis.call('DEL', KEYS[i])
    released = released + 1
  end
end
return released

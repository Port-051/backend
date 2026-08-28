-- 파티 후보 전원을 한 번에 선점한다. all-or-nothing.
--
-- KEYS  claim:{requestId} ... (후보 전원)
-- ARGV  [1] 선점 토큰   [2] TTL(ms)
--
-- 반환  1 = 전원 선점 성공, 0 = 하나라도 이미 잡혀 있어 아무것도 잡지 않음
--
-- 왜 Lua인가. 후보를 하나씩 SET NX 하면 중간까지 성공하고 마지막에 실패하는 상태가 생긴다.
-- 그 사이 다른 인스턴스는 이미 잡힌 앞쪽을 보고 물러나므로, 아무도 성립시키지 못하는
-- 교착이 부하 구간에서 반복된다. Lua 안에서는 검사와 쓰기 사이에 다른 명령이 끼지 못한다.

for i = 1, #KEYS do
  if redis.call('EXISTS', KEYS[i]) == 1 then
    return 0
  end
end

for i = 1, #KEYS do
  redis.call('SET', KEYS[i], ARGV[1], 'PX', ARGV[2])
end

return 1

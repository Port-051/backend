-- 요청 취소. 01 3.9 — 취소와 제안 생성이 동시에 일어나면
-- "요청 상태 전이에 먼저 성공한 쪽이 이긴다".
--
-- KEYS  [1] mq:{queue}:{size}  [2] reqstate:{id}  [3] req:{id}
--       [4] claim:{id}         [5] reqoffer:{id}
-- ARGV  [1] requestId
--
-- 반환  'CANCELLED'      = 취소 성공
--       'OFFERED:{id}'   = 제안이 먼저 선점했다. 취소는 실패하고 거절 경로로 보낸다
--       'NOT_WAITING'    = 대기 중이 아니다 (이미 확정·취소·실패)
--
-- 반환값을 전부 문자열로 맞춘다. Lua가 숫자와 문자열을 섞어 돌려주면 Lettuce가
-- 하나의 출력 타입으로 읽지 못하고 UnsupportedOperationException으로 죽는다.
--
-- 선점(claim)이 붙어 있으면 매처가 이 요청으로 파티를 짜는 중이다. 그 상태에서 명단만
-- 지우면 매처는 이미 후보를 손에 들고 있어 취소된 요청으로 파티를 확정한다.
-- 그래서 claim의 존재를 "제안이 이겼다"의 판정 근거로 쓴다.

local state = redis.call('GET', KEYS[2])
if state ~= 'WAITING' then
  if state == 'OFFERED' then
    local offerId = redis.call('GET', KEYS[5])
    return 'OFFERED:' .. (offerId or '')
  end
  return 'NOT_WAITING'
end

if redis.call('EXISTS', KEYS[4]) == 1 then
  local offerId = redis.call('GET', KEYS[5])
  return 'OFFERED:' .. (offerId or '')
end

redis.call('ZREM', KEYS[1], string.format('%019d', tonumber(ARGV[1])))
redis.call('SET', KEYS[2], 'CANCELLED')
redis.call('DEL', KEYS[3])
return 'CANCELLED'

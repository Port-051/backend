package com.port051.queuemate.matching.store;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 배정 중 표시 {@code claim:{requestId}}. 05-realtime-matching-contract 1장 · 3장.
 *
 * <p>계약이 정한 규칙은 <b>"찾은 전원에게 동시에 claim을 건다. 하나라도 이미 걸려 있으면
 * 전부 취소하고 다음 사이클"</b>이다. 전원 아니면 아무도 아니어야 한다.
 *
 * <p><b>Lua를 쓰는 이유가 있다.</b> Redis에는 롤백이 없다. {@code MULTI}는 명령을 묶어 보낼 뿐이라
 * 세 번째에서 실패해도 앞의 둘이 되돌아가지 않는다. 자바에서 하나씩 {@code SET NX}를 걸고
 * 실패하면 지우는 방법도 있지만, 그 사이에 다른 인스턴스가 끼어들어 <b>양쪽 다 일부만 쥔 채
 * 서로를 막는</b> 상태가 된다. Redis는 스크립트를 통째로 실행하는 동안 다른 명령을 받지 않으므로,
 * 검사와 설정을 한 스크립트에 넣으면 그 틈이 사라진다.
 *
 * <p><b>소유자 토큰을 두는 이유도 있다.</b> TTL이 먼저 끝나 남이 같은 요청을 잡았을 때,
 * 뒤늦게 도착한 우리 해제가 남의 claim을 지우면 안 된다. 그래서 해제는 값이 내 토큰일 때만 지운다.
 */
@Component
public class ClaimStore {

    /**
     * 전원을 잡거나 아무도 잡지 않는다.
     *
     * <p>전부 확인한 뒤에 전부 설정한다. 중간에 다른 명령이 끼어들 수 없으므로
     * 확인 시점의 판단이 설정 시점까지 그대로 유효하다.
     */
    private static final RedisScript<Long> CLAIM_ALL = new DefaultRedisScript<>("""
            for i = 1, #KEYS do
              if redis.call('EXISTS', KEYS[i]) == 1 then
                return 0
              end
            end
            for i = 1, #KEYS do
              redis.call('SET', KEYS[i], ARGV[1], 'PX', ARGV[2])
            end
            return 1
            """, Long.class);

    /** 내 토큰이 든 것만 지운다. 남이 잡은 것은 건드리지 않는다. */
    private static final RedisScript<Long> RELEASE_ALL = new DefaultRedisScript<>("""
            local released = 0
            for i = 1, #KEYS do
              if redis.call('GET', KEYS[i]) == ARGV[1] then
                redis.call('DEL', KEYS[i])
                released = released + 1
              end
            end
            return released
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public ClaimStore(StringRedisTemplate redis,
                      @Value("${queuemate.matching.claim-ttl:3s}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    /**
     * 요청 전원에게 표시를 건다.
     *
     * <p>하나라도 이미 잡혀 있으면 아무것도 걸지 않고 실패한다. 부분적으로 걸린 상태는 남지 않는다.
     *
     * @return 성공하면 소유자 토큰. 해제할 때 이 값이 필요하다
     */
    public Optional<String> claimAll(List<Long> requestIds) {
        if (requestIds.isEmpty()) {
            // 잡을 것이 없다. 호출부가 파티를 쥐었다고 착각하면 안 되므로 토큰을 주지 않는다.
            return Optional.empty();
        }
        String owner = UUID.randomUUID().toString();
        Long claimed = redis.execute(CLAIM_ALL, keys(requestIds), owner, String.valueOf(ttl.toMillis()));
        return claimed != null && claimed == 1 ? Optional.of(owner) : Optional.empty();
    }

    /**
     * 잡아둔 표시를 뗀다.
     *
     * <p>파티가 성립해 요청이 명단에서 빠졌거나, 성립에 실패해 되돌릴 때 부른다.
     * 떼지 않고 두어도 TTL이 지나면 풀리지만 그만큼 그 요청이 묶여 있다.
     *
     * @return 실제로 뗀 개수. 토큰이 다르거나 이미 만료됐으면 그만큼 적다
     */
    public long releaseAll(List<Long> requestIds, String owner) {
        if (requestIds.isEmpty()) {
            return 0;
        }
        Long released = redis.execute(RELEASE_ALL, keys(requestIds), owner);
        return released == null ? 0 : released;
    }

    /** 지금 잡혀 있는지. 진단과 테스트에 쓴다. */
    public boolean isClaimed(long requestId) {
        return Boolean.TRUE.equals(redis.hasKey(key(requestId)));
    }

    /** 설정된 표시 유지 시간. */
    public Duration ttl() {
        return ttl;
    }

    private static List<String> keys(List<Long> requestIds) {
        return requestIds.stream().map(ClaimStore::key).toList();
    }

    private static String key(long requestId) {
        return "claim:" + requestId;
    }
}

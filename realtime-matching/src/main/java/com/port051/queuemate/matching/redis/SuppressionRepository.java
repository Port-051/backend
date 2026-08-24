package com.port051.queuemate.matching.redis;

import java.time.Duration;
import java.util.Collection;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 01 5.4 — 명시적 거절로 깨진 조합만 10분간 억제한다. 시간초과는 억제하지 않는다.
 * 억제 기록은 인스턴스 간에 공유돼야 하므로 Redis에 둔다.
 */
@Component
public class SuppressionRepository {

    private final StringRedisTemplate redis;

    public SuppressionRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean allowed(Set<Long> combo) {
        return !Boolean.TRUE.equals(redis.hasKey(Keys.combo(combo)));
    }

    public void suppress(Collection<Long> combo, Duration ttl) {
        redis.opsForValue().set(Keys.combo(combo), "DECLINED", ttl);
    }
}

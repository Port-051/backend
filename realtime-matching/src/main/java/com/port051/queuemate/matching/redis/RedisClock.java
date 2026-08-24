package com.port051.queuemate.matching.redis;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 02 1.5 — 만료·마감 판정은 애플리케이션 시계가 아니라 <b>공용 시계</b>로 한다.
 * 명세는 데이터베이스 시계를 지정하지만 실시간 매칭에는 DB가 없다.
 * 두 인스턴스가 같이 보는 시계는 Redis뿐이므로 여기서는 Redis {@code TIME}이 그 역할을 한다.
 */
@Component
public class RedisClock {

    private static final RedisScript<Long> NOW = RedisScript.of(
            "local t = redis.call('TIME') return t[1] * 1000 + math.floor(t[2] / 1000)", Long.class);

    private final StringRedisTemplate redis;

    public RedisClock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public long nowMillis() {
        Long now = redis.execute(NOW, List.of());
        if (now == null) {
            throw new IllegalStateException("Redis TIME 조회 실패 — 공용 시계 없이는 만료를 판정할 수 없다");
        }
        return now;
    }
}

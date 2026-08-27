package com.port051.queuemate.matching.store;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 만료 판정에 쓰는 시계. 02-technical-spec-supplement 1.5.
 *
 * <p>문서는 "만료·마감·배치 기준 시각은 애플리케이션 시계가 아니라 <b>데이터베이스 시계</b>를
 * 사용한다"고 정한다. 이 단계에는 데이터베이스가 없으므로 그 자리를 Redis가 대신한다.
 * 중요한 것은 어느 저장소냐가 아니라 <b>모든 인스턴스가 같은 시계를 본다</b>는 것이다.
 *
 * <p>{@code System.currentTimeMillis()}를 쓰면 인스턴스마다 시계가 조금씩 달라
 * 같은 요청이 어디서 판정되느냐에 따라 만료가 되기도 하고 안 되기도 한다.
 * 그러면 같은 입력에 같은 결과라는 4.3의 결정성이 깨진다.
 */
@Component
public class RedisClock {

    private final StringRedisTemplate redis;

    public RedisClock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 현재 시각(epoch millis). 요청의 {@code requestedAt}과 같은 단위다. */
    public long nowMillis() {
        Long time = redis.execute((RedisCallback<Long>) connection -> connection.serverCommands().time());
        if (time == null) {
            throw new IllegalStateException("Redis 시계를 읽지 못했다");
        }
        return time;
    }
}

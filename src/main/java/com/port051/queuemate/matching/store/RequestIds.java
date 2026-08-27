package com.port051.queuemate.matching.store;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 요청 식별자를 발급한다.
 *
 * <p>클라이언트가 정하게 두지 않는다. 요청 ID는 4.3의 전순서에서 <b>신청 시각이 같을 때
 * 순서를 가르는 값</b>이라, 아무 값이나 들어오면 먼저 신청한 사람이 뒤로 밀릴 수 있다.
 * 중복도 막아야 하는데 그건 서버만 보장할 수 있다.
 *
 * <p>{@code INCR}는 원자적이라 인스턴스가 몇 대든 같은 번호를 두 번 주지 않는다.
 */
@Component
public class RequestIds {

    private final StringRedisTemplate redis;

    public RequestIds(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 다음 요청 ID. 발급 순서가 곧 증가 순서다. */
    public long next() {
        Long id = redis.opsForValue().increment("seq:request");
        if (id == null) {
            throw new IllegalStateException("요청 ID를 발급하지 못했다");
        }
        return id;
    }
}

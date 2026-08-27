package com.port051.queuemate.matching.sse;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * 소식을 모든 인스턴스에 뿌린다. 05-realtime-matching-contract 3장 — "확정을 PUBLISH로 알린다".
 *
 * <p><b>채널을 하나만 쓴다.</b> 사용자별 채널을 두면 인스턴스가 받는 양이 줄지만,
 * 연결이 붙고 끊길 때마다 구독을 붙였다 떼야 한다. 그 관리가 틀리면 소식이 조용히 사라지는데,
 * 조용히 사라지는 실패는 찾기 어렵다. 채널 하나에 전부 흘리고 각자 거르는 쪽이
 * 이 규모에서는 낭비보다 안전이 크다.
 *
 * <p><b>발행 시점이 중요하다.</b> 02-technical-spec-supplement 3.2는 확정이 저장된 <b>뒤에</b>
 * 발행하라고 정한다. 먼저 발행하면 확정에 실패했는데 "파티가 잡혔다"는 소식이 이미 나간 상태가 된다.
 */
@Component
public class EventPublisher {

    /** 이 단계의 소식은 종류가 둘뿐이라 채널을 나눌 이유가 없다. */
    static final String CHANNEL = "matching:events";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final StringRedisTemplate redis;

    public EventPublisher(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void publish(MatchingEvent event) {
        redis.convertAndSend(CHANNEL, JSON.writeValueAsString(event));
    }

    public void publishAll(List<MatchingEvent> events) {
        for (MatchingEvent event : events) {
            publish(event);
        }
    }
}

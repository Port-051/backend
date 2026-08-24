package com.port051.queuemate.matching.redis;

import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class EventPublisher {

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final RedisClock clock;

    public EventPublisher(StringRedisTemplate redis, ObjectMapper json, RedisClock clock) {
        this.redis = redis;
        this.json = json;
        this.clock = clock;
    }

    public void publish(String type, List<Long> userIds, Map<String, Object> payload) {
        MatchingEvent event = new MatchingEvent(type, userIds, payload, clock.nowMillis());
        redis.convertAndSend(Keys.EVENT_CHANNEL, json.writeValueAsString(event));
    }
}

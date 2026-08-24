package com.port051.queuemate.matching.sse;

import com.port051.queuemate.matching.redis.MatchingEvent;
import java.nio.charset.StandardCharsets;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Redis Pub/Sub 구독자. 인스턴스 간 팬아웃의 수신 쪽이다(02 3.2). */
@Component
public class MatchingEventListener implements MessageListener {

    private final SseHub hub;
    private final ObjectMapper json;

    public MatchingEventListener(SseHub hub, ObjectMapper json) {
        this.hub = hub;
        this.json = json;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        MatchingEvent event = json.readValue(
                new String(message.getBody(), StandardCharsets.UTF_8), MatchingEvent.class);
        hub.deliver(event);
    }
}

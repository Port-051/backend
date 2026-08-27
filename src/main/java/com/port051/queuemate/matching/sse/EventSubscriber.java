package com.port051.queuemate.matching.sse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

/**
 * 뿌려진 소식을 받아 이 인스턴스에 붙은 연결로 넘긴다.
 *
 * <p>모든 인스턴스가 모든 소식을 받는다. 그중 <b>자기에게 붙어 있는 사용자의 것만</b>
 * 실제로 나간다. 나머지는 {@link EmitterRegistry}가 조용히 버린다.
 *
 * <p>여기서 예외가 새면 리스너 컨테이너가 그 연결을 접을 수 있다. 소식 하나를 못 보내는 것보다
 * 이후 소식을 전부 못 받는 쪽이 훨씬 나쁘므로 잡아서 기록만 한다.
 */
@Component
public class EventSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(EventSubscriber.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final EmitterRegistry emitters;

    public EventSubscriber(EmitterRegistry emitters) {
        this.emitters = emitters;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            MatchingEvent event = JSON.readValue(
                    new String(message.getBody(), StandardCharsets.UTF_8), MatchingEvent.class);
            emitters.send(event);
        } catch (RuntimeException e) {
            log.error("소식을 전달하지 못했다", e);
        }
    }
}

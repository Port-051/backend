package com.port051.queuemate.sse;

import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 인스턴스 간 이벤트 전파. 02 3장.
 *
 * <p><b>왜 Pub/Sub을 거치는가.</b> 제안 1건은 참가자 전원에게 같은 시점에 도착해야 하는데
 * (02 3.1), 참가자들은 LB에 의해 서로 다른 인스턴스에 붙어 있다. 매칭 루프를 돌린 인스턴스가
 * 자기 연결로만 내보내면 다른 인스턴스에 붙은 참가자는 아무것도 받지 못한다.
 *
 * <p>발행 측은 참가자가 어디 붙었는지 모르고, 구독 측은 자기에게 붙은 userId만 골라 내보낸다.
 * 어느 쪽도 연결 위치를 알 필요가 없다.
 *
 * <p><b>먼저 받은 사람이 유리해지면 안 된다</b>(02 3.1). 그래서 수락 제한시간은 전달 시각이 아니라
 * 제안 생성 시각을 기준으로 계산한다 — {@code offer:{id}}의 {@code expiresAt}이 그 값이고,
 * 이 클래스는 전달만 한다.
 */
@Component
public class EventFanout implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(EventFanout.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final SseHub hub;

    public EventFanout(StringRedisTemplate redis, ObjectMapper objectMapper, SseHub hub) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.hub = hub;
    }

    /** 이벤트 하나를 전 인스턴스에 발행한다. */
    public void publish(MatchEvent event) {
        try {
            redis.convertAndSend(
                    com.port051.queuemate.contract.RedisKeys.EVENT_CHANNEL,
                    objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("이벤트 발행 실패 type={} userId={}", event.type(), event.userId(), e);
        }
    }

    /** 여러 건을 한 번에. 제안 팬아웃처럼 참가자 전원에게 동시에 나가야 하는 경우다. */
    public void publishAll(List<MatchEvent> events) {
        events.forEach(this::publish);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String json = new String(message.getBody(), StandardCharsets.UTF_8);
            MatchEvent event = objectMapper.readValue(json, MatchEvent.class);
            hub.deliver(event.userId(), event.type().name(), json);
        } catch (Exception e) {
            log.warn("이벤트 수신 처리 실패", e);
        }
    }
}

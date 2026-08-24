package com.port051.queuemate.matching.sse;

import com.port051.queuemate.matching.redis.MatchingEvent;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 01 8.1 · 02 3.2 — 이 인스턴스에 붙은 SSE 연결만 들고 있다.
 * 다른 인스턴스에 붙은 참가자에게는 Pub/Sub이 대신 닿는다.
 *
 * <p>02 3.4 — 끊긴 동안의 이벤트는 재전송하지 않는다. 재연결하면
 * {@code GET /api/me/state}로 현재 상태를 다시 조회해 화면을 맞춘다.
 */
@Component
public class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);

    private final Map<Long, Set<SseEmitter>> connections = new ConcurrentHashMap<>();
    private final String instanceId;

    public SseHub(@Value("${matching.instance-id}") String instanceId) {
        this.instanceId = instanceId;
    }

    public SseEmitter register(long userId) {
        SseEmitter emitter = new SseEmitter(0L);   // 만료 없음. 가상 스레드라 연결당 비용이 낮다(04 2.1)
        connections.computeIfAbsent(userId, key -> new CopyOnWriteArraySet<>()).add(emitter);
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(error -> remove(userId, emitter));
        send(emitter, "CONNECTED", Map.of("userId", userId, "instance", instanceId));
        return emitter;
    }

    /** 이벤트에 실린 사용자 중 이 인스턴스에 붙어 있는 연결에만 내보낸다. */
    public int deliver(MatchingEvent event) {
        int delivered = 0;
        for (Long userId : event.userIds()) {
            for (SseEmitter emitter : connections.getOrDefault(userId, Set.of())) {
                Map<String, Object> body = new HashMap<>(event.payload());
                body.put("emittedAt", event.emittedAt());   // 02 6.2 — 전달 지연은 단일 시계로 잰다
                if (send(emitter, event.type(), body)) {
                    delivered++;
                }
            }
        }
        return delivered;
    }

    @Scheduled(fixedDelayString = "${matching.heartbeat}")
    public void heartbeat() {
        connections.forEach((userId, emitters) ->
                emitters.forEach(emitter -> send(emitter, "HEARTBEAT", Map.of())));
    }

    public int connectionCount() {
        return connections.values().stream().mapToInt(Set::size).sum();
    }

    private boolean send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 전송 실패 — 연결을 정리한다: {}", e.toString());
            emitter.completeWithError(e);
            return false;
        }
    }

    private void remove(long userId, SseEmitter emitter) {
        Set<SseEmitter> emitters = connections.get(userId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            connections.remove(userId, emitters);
        }
    }
}

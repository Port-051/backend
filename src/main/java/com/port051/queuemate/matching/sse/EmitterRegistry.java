package com.port051.queuemate.matching.sse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 이 인스턴스에 붙어 있는 연결만 들고 있는다. 02-technical-spec-supplement 1.1.
 *
 * <p>SSE 연결은 한 인스턴스에 고정된다. 그래서 이 목록은 <b>인스턴스마다 다르고</b>
 * 공유되지 않는다. 다른 인스턴스에서 성립한 파티를 여기 붙은 사용자에게 전달하려면
 * Pub/Sub이 필요하며, 그것이 {@link EventSubscriber}가 하는 일이다.
 *
 * <p>사용자 하나에 연결이 여럿일 수 있다. 탭을 두 개 열어 두면 그렇게 된다.
 */
@Component
public class EmitterRegistry {

    private static final Logger log = LoggerFactory.getLogger(EmitterRegistry.class);

    private final Map<Long, List<SseEmitter>> byUser = new ConcurrentHashMap<>();

    /** 연결을 등록한다. 끊기면 스스로 빠진다. */
    public SseEmitter add(long userId, SseEmitter emitter) {
        byUser.computeIfAbsent(userId, id -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(e -> remove(userId, emitter));
        return emitter;
    }

    /**
     * 이 인스턴스에 붙은 연결로 소식을 내보낸다.
     *
     * @return 실제로 보낸 연결 수. 이 사용자가 다른 인스턴스에 붙어 있으면 0이다
     */
    public int send(MatchingEvent event) {
        List<SseEmitter> emitters = byUser.get(event.userId());
        if (emitters == null || emitters.isEmpty()) {
            return 0;
        }

        int sent = 0;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(event.type().name()).data(event));
                sent++;
            } catch (IOException | IllegalStateException e) {
                // 이미 끊긴 연결이다. 콜백이 정리하지만 여기서도 빼 둔다.
                log.debug("연결이 끊겨 있어 건너뛴다: 사용자 {}", event.userId());
                remove(event.userId(), emitter);
            }
        }
        return sent;
    }

    /** 이 인스턴스에 붙어 있는 연결 수. 진단과 테스트에 쓴다. */
    public int connectionCount(long userId) {
        List<SseEmitter> emitters = byUser.get(userId);
        return emitters == null ? 0 : emitters.size();
    }

    private void remove(long userId, SseEmitter emitter) {
        byUser.computeIfPresent(userId, (id, emitters) -> {
            emitters.remove(emitter);
            // 빈 목록을 남겨 두면 접속했다 떠난 사용자만큼 지도가 자란다.
            return emitters.isEmpty() ? null : emitters;
        });
    }
}

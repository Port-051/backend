package com.port051.queuemate.sse;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * <b>이 인스턴스에</b> 붙어 있는 SSE 연결만 들고 있다. 인스턴스 간 전파는 {@link EventFanout}이 한다.
 *
 * <p>한 사용자가 탭을 여러 개 열 수 있으므로 userId 하나에 연결이 여럿이다.
 *
 * <p>연결이 끊긴 동안 발생한 이벤트는 <b>재전송하지 않는다</b>(02 3.4).
 * 재연결한 클라이언트는 {@code GET /api/me/state}로 현재 상태를 통째로 다시 읽어 화면을 맞춘다.
 * 이벤트를 쌓아두고 재생하려면 연결별 커서와 보관 기간이 필요한데, 상태를 다시 읽으면
 * 그게 전부 없어도 화면이 맞는다.
 */
@Component
public class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);

    private final Map<Long, List<SseEmitter>> connections = new ConcurrentHashMap<>();

    public SseEmitter open(long userId, long timeoutMillis) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        connections.computeIfAbsent(userId, id -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(error -> remove(userId, emitter));

        try {
            // 첫 바이트를 바로 흘려 프록시가 연결을 붙잡고 있지 않게 한다.
            emitter.send(SseEmitter.event().name("CONNECTED").data("{\"userId\":" + userId + "}"));
        } catch (IOException e) {
            remove(userId, emitter);
        }
        return emitter;
    }

    /** 이 인스턴스에 붙은 연결로만 내보낸다. 붙어 있지 않으면 아무 일도 하지 않는다. */
    public void deliver(long userId, String eventName, String json) {
        List<SseEmitter> targets = connections.get(userId);
        if (targets == null || targets.isEmpty()) return;

        for (SseEmitter emitter : targets) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(json));
            } catch (Exception e) {
                // 끊긴 연결이다. 02 3.2 — 전달 실패는 기록하되 상태의 권위는 저장소에 있으므로
                // 클라이언트가 재조회로 복구한다.
                log.debug("SSE 전달 실패 userId={} event={}", userId, eventName, e);
                remove(userId, emitter);
            }
        }
    }

    private void remove(long userId, SseEmitter emitter) {
        List<SseEmitter> targets = connections.get(userId);
        if (targets == null) return;
        targets.remove(emitter);
        if (targets.isEmpty()) connections.remove(userId, targets);
    }

    public int connectionCount() {
        return connections.values().stream().mapToInt(List::size).sum();
    }
}

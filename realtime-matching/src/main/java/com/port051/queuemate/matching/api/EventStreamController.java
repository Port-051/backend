package com.port051.queuemate.matching.api;

import com.port051.queuemate.matching.domain.MatchRequestView;
import com.port051.queuemate.matching.redis.RequestRepository;
import com.port051.queuemate.matching.sse.SseHub;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 01 8.1 — 인앱 실시간 상태. 화면이 열려 있으면 이 채널만으로 모든 알림이 전달된다.
 * Web Push(01 8.2)는 이 스파이크 범위 밖이다.
 */
@RestController
public class EventStreamController {

    private final SseHub hub;
    private final RequestRepository requests;

    public EventStreamController(SseHub hub, RequestRepository requests) {
        this.hub = hub;
        this.requests = requests;
    }

    @GetMapping(path = "/api/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam long userId) {
        return hub.register(userId);
    }

    /**
     * 02 3.4 — 재연결 시 현재 상태 전체를 다시 조회한다. 끊긴 동안의 이벤트는 재전송하지 않는다.
     */
    @GetMapping("/api/me/state")
    public Map<String, Object> state(@RequestParam long userId) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Long requestId : requests.requestIdsOf(userId)) {
            Optional<MatchRequestView> request = requests.find(requestId);
            if (request.isEmpty()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("requestId", requestId);
            item.put("queue", request.get().queue());
            item.put("targetSize", request.get().targetSize());
            item.put("expiresAt", request.get().expiresAt());
            // 제안 진행 상태(offerId·offer)는 선점 방식에 딸린 값이라 비교 축이다.
            // 각자 브랜치에서 이 자리에 채운다.
            items.add(item);
        }
        return Map.of("userId", userId, "requests", items, "connections", hub.connectionCount());
    }
}

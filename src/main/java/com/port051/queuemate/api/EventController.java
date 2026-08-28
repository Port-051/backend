package com.port051.queuemate.api;

import com.port051.queuemate.sse.SseHub;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE. 01 8.1 · 02 3장.
 *
 * <p><b>WebSocket이 아니라 SSE를 고른 이유</b>(02 3.2가 근거를 남기라고 했다).
 * 이 구간에서 오가는 것은 전부 <b>서버 → 클라이언트 단방향</b>이다 — 제안 도착, 응답 현황,
 * 확정, 만료. 클라이언트가 서버에 보내는 것(수락·거절·취소)은 이미 REST로 있고,
 * 그쪽은 멱등성과 상태코드가 필요해서 오히려 HTTP가 맞다.
 *
 * <p>WebSocket을 쓰면 얻는 것은 양방향인데 쓸 데가 없고, 잃는 것은 분명하다 —
 * 프로토콜 업그레이드가 붙어 LB·프록시 설정이 늘고, 재연결·하트비트를 직접 짜야 한다.
 * SSE는 브라우저가 자동 재연결을 해준다.
 *
 * <p>SSE의 대가는 <b>연결이 인스턴스에 고정된다</b>는 것이다. 그래서 제안 전파에
 * Redis Pub/Sub이 필요하다(02 1.1) — 그게 이 스파이크의 과제 중 하나라 대가가 아니라 재료다.
 */
@RestController
@RequestMapping("/api")
public class EventController {

    private static final Duration TIMEOUT = Duration.ofMinutes(30);

    private final SseHub hub;

    public EventController(SseHub hub) {
        this.hub = hub;
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@RequestParam long userId) {
        return hub.open(userId, TIMEOUT.toMillis());
    }
}

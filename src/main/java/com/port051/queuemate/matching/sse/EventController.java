package com.port051.queuemate.matching.sse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;

/**
 * 매칭 소식을 실시간으로 내보낸다. 05-realtime-matching-contract "이번 단계의 범위".
 *
 * <p>요청이 아니라 <b>사용자</b> 단위로 연다. 9.2가 사용자당 대기 요청을 하나로 묶어 두므로
 * 둘은 사실상 같지만, 사용자 기준이면 요청이 끝나고 다시 신청해도 연결을 이어 쓸 수 있다.
 *
 * <p>연결 하나가 스레드 하나를 붙잡는다. 가상 스레드를 켜 둔 덕에
 * ({@code spring.threads.virtual.enabled}) 대기자 수만큼 연결이 늘어도 톰캣 스레드가 마르지 않는다.
 */
@RestController
public class EventController {

    private final EmitterRegistry emitters;
    private final Duration timeout;

    public EventController(EmitterRegistry emitters,
                           @Value("${queuemate.matching.sse-timeout:6m}") Duration timeout) {
        this.emitters = emitters;
        // 요청이 만료될 때까지는 열려 있어야 한다. 만료 소식을 받을 연결이 먼저 닫히면 안 된다.
        // 그래서 기본값이 최대 대기시간보다 길다. 설정으로 뺀 이유는 테스트가 6분을 기다릴 수 없어서다.
        this.timeout = timeout;
    }

    @GetMapping(value = "/api/users/{userId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@PathVariable long userId) {
        return emitters.add(userId, new SseEmitter(timeout.toMillis()));
    }
}

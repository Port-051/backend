package com.port051.queuemate.matching.sse;

import com.port051.queuemate.matching.domain.GameQueue;
import com.port051.queuemate.matching.domain.Position;
import com.port051.queuemate.matching.domain.Purpose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 연결 등록소. 이 인스턴스에 붙은 연결만 다루므로 Redis도 스프링도 필요 없다.
 */
class EmitterRegistryTest {

    private final EmitterRegistry registry = new EmitterRegistry();

    private static MatchingEvent eventFor(long userId) {
        return new MatchingEvent(
                MatchingEvent.Type.PARTY_CONFIRMED, userId, 1L,
                GameQueue.SOLO_DUO, 2, Purpose.RANK_UP, Position.MID, false);
    }

    @Test
    @DisplayName("등록하면 센다")
    void countsRegisteredConnections() {
        registry.add(7L, new SseEmitter(1_000L));

        assertThat(registry.connectionCount(7L)).isEqualTo(1);
    }

    @Test
    @DisplayName("한 사용자가 연결을 여럿 가질 수 있다")
    void oneUserCanHaveSeveralConnections() {
        registry.add(7L, new SseEmitter(1_000L));
        registry.add(7L, new SseEmitter(1_000L));

        assertThat(registry.connectionCount(7L)).isEqualTo(2);
    }

    @Test
    @DisplayName("붙어 있지 않은 사용자는 0이다")
    void unknownUsersHaveNoConnections() {
        assertThat(registry.connectionCount(999L)).isZero();
    }

    /**
     * 끝난 연결이 빠지는 경로는 둘이다. 하나는 SSE 콜백({@code onCompletion} 등)이고
     * 다른 하나는 보내려다 실패하는 것이다. 콜백은 실제 HTTP 응답에 붙어 있어야 울리므로
     * 여기서는 확인할 수 없고, {@code EventControllerTest}가 실 연결로 덮는다.
     */
    @Test
    @DisplayName("이미 끝난 연결로 보내려 하면 등록에서 빠진다")
    void sendingToADeadConnectionCleansItUp() {
        SseEmitter emitter = registry.add(7L, new SseEmitter(1_000L));
        emitter.complete();

        int sent = registry.send(eventFor(7L));

        assertThat(sent).isZero();
        assertThat(registry.connectionCount(7L)).isZero();
    }

    @Test
    @DisplayName("붙어 있지 않은 사용자의 소식은 아무 데도 안 간다")
    void eventsForAbsentUsersGoNowhere() {
        registry.add(7L, new SseEmitter(1_000L));

        assertThat(registry.send(eventFor(8L))).isZero();
    }
}

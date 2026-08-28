package com.port051.queuemate.sse;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * 한 사용자에게 보내는 이벤트 한 건.
 *
 * <p>{@code userId}는 <b>받을 사람</b>이다. 발행은 인스턴스 구분 없이 한 채널로 나가고,
 * 각 인스턴스가 자기에게 붙은 연결만 골라 내보낸다(02 3.2).
 *
 * @param occurredAt 발생 시각. 02 6.2가 "제안 전달 지연은 커밋 시각 기준"이라고 했으므로
 *                   전달 지연은 이 값과 클라이언트 수신 시각의 차이로 잰다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MatchEvent(
        EventType type,
        long userId,
        Long requestId,
        Long offerId,
        Long partyId,
        long occurredAt,
        Map<String, Object> data) {

    public static MatchEvent of(EventType type, long userId, Long requestId, Long offerId) {
        return new MatchEvent(type, userId, requestId, offerId, null, System.currentTimeMillis(), Map.of());
    }

    public static MatchEvent of(
            EventType type, long userId, Long requestId, Long offerId, Map<String, Object> data) {
        return new MatchEvent(type, userId, requestId, offerId, null, System.currentTimeMillis(), data);
    }

    public MatchEvent withParty(long partyId) {
        return new MatchEvent(type, userId, requestId, offerId, partyId, occurredAt, data);
    }
}

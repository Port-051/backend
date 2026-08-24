package com.port051.queuemate.matching.redis;

import java.util.List;
import java.util.Map;

/**
 * 인스턴스 사이를 지나는 이벤트. 수신 인스턴스는 {@code userIds} 중
 * 자신에게 붙은 SSE 연결에만 내보낸다(02 3.2).
 *
 * <p>{@code emittedAt}은 발행 인스턴스의 Redis 시계 값이다. 전달 지연(02 6.2)을
 * 단일 시계 기준으로 재기 위해 이벤트에 실어 보낸다.
 */
public record MatchingEvent(String type, List<Long> userIds, Map<String, Object> payload, long emittedAt) {

    public static final String OFFER_CREATED = "OFFER_CREATED";
    public static final String OFFER_RESPONSE_UPDATED = "OFFER_RESPONSE_UPDATED";
    public static final String OFFER_DECLINED = "OFFER_DECLINED";
    public static final String OFFER_EXPIRED = "OFFER_EXPIRED";
    public static final String PARTY_CONFIRMED = "PARTY_CONFIRMED";
    public static final String REQUEST_CANCELLED = "REQUEST_CANCELLED";
    public static final String MATCH_FAILED = "MATCH_FAILED";
}

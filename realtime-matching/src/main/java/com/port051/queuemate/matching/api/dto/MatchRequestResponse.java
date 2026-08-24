package com.port051.queuemate.matching.api.dto;

import com.port051.queuemate.matching.domain.MatchRequestView;

/**
 * 01 6.1의 상태 표기를 그대로 쓴다. 서버 내부 처리 단계는 노출하지 않는다.
 * {@code waitingCount}는 01 0.1 — 조건 입력 화면에 현재 대기 인원을 표시하기 위한 값이다.
 */
public record MatchRequestResponse(
        long requestId,
        String state,
        long requestedAt,
        long expiresAt,
        long waitingCount,
        Long offerId
) {
    public static final String WAITING = "매칭 대기 중";
    public static final String OFFERED = "수락 대기 중";

    public static MatchRequestResponse waiting(MatchRequestView request, long waitingCount) {
        return new MatchRequestResponse(request.requestId(), WAITING,
                request.requestedAt(), request.expiresAt(), waitingCount, null);
    }
}

package com.port051.queuemate.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.port051.queuemate.contract.RequestState;

/** 계약(이슈 #48 API 계약)이 정한 응답 모양들. */
public final class Responses {

    private Responses() {}

    /** {@code POST /api/match-requests} → 201 */
    public record Created(long requestId, RequestState state) {}

    /**
     * {@code GET /api/match-requests/{id}} → 200.
     * 제안이 진행 중이면 {@code offerId}를 채운다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RequestStatus(
            long requestId, RequestState state, Long offerId, Long partyId, Long expiresAt) {}

    /** {@code DELETE /api/match-requests/{id}} → 200 */
    public record Cancelled(long requestId, RequestState state) {}

    /**
     * {@code DELETE /api/match-requests/{id}} → 409.
     * 01 3.9 — 제안 생성이 이겼으면 취소는 실패하고 거절 경로를 제시한다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Conflict(String message, Long offerId) {}

    /** 수락·거절 응답. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OfferResponse(
            long offerId, long requestId, String outcome, int acceptedCount, String message) {}

    /**
     * {@code GET /api/me/state?userId=N} → 200.
     * 02 3.4 — 재연결 시 현재 상태 전체를 다시 조회해서 화면을 맞춘다.
     */
    public record UserState(long userId, java.util.List<RequestStatus> requests) {}
}

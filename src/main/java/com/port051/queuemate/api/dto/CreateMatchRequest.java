package com.port051.queuemate.api.dto;

import com.port051.queuemate.contract.MatchRequestPayload;
import com.port051.queuemate.contract.Position;
import com.port051.queuemate.contract.Purpose;
import com.port051.queuemate.contract.Queue;
import com.port051.queuemate.contract.VoiceMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * {@code POST /api/match-requests} 본문.
 *
 * <p>계약(05 2절)의 {@code req:} JSON에서 {@code requestId}만 뺀 모양이다.
 * 요청 id는 서버가 부여하고 201 응답으로 돌려준다 — 클라이언트가 정하면 충돌을 막을 방법이 없다.
 *
 * <p>{@code requestedAt}도 클라이언트가 보내지 않는다. 01 4.3의 전순서가 이 값에 걸려 있는데
 * 클라이언트 시계를 믿으면 순서가 시계 오차로 뒤집힌다. 02 1.5가 "만료·마감·배치 기준 시각은
 * 애플리케이션 시계가 아니라 데이터베이스 시계"라고 한 것과 같은 이유이고,
 * DB가 없는 이 스파이크에서는 그 자리를 <b>요청을 받은 서버</b>가 대신한다.
 */
public record CreateMatchRequest(
        @NotNull Long userId,
        @NotNull Queue queue,
        @Min(2) int targetSize,
        @NotNull Purpose purpose,
        @Min(1) int playMinutes,
        @NotNull VoiceMode voiceMode,
        @NotNull Position primaryPosition,
        List<Position> subPositions,
        @Min(1) int tierOrder,
        @Min(1) int allowedTierMinOrder,
        @Min(1) int allowedTierMaxOrder) {

    public MatchRequestPayload toPayload(long requestId, long requestedAt) {
        List<Position> subs =
                subPositions == null
                        ? List.of()
                        : subPositions.stream().filter(p -> p != primaryPosition).distinct().toList();
        return new MatchRequestPayload(
                requestId,
                userId,
                queue,
                targetSize,
                purpose,
                playMinutes,
                voiceMode,
                primaryPosition,
                subs,
                tierOrder,
                allowedTierMinOrder,
                allowedTierMaxOrder,
                requestedAt);
    }
}

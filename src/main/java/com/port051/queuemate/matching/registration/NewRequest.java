package com.port051.queuemate.matching.registration;

import com.port051.queuemate.matching.domain.GameQueue;
import com.port051.queuemate.matching.domain.MatchRequest;
import com.port051.queuemate.matching.domain.Position;
import com.port051.queuemate.matching.domain.Purpose;
import com.port051.queuemate.matching.domain.VoiceMode;

import java.util.List;

/**
 * 접수 전의 요청. 사용자가 고른 조건만 담는다.
 *
 * <p>{@code requestId}와 {@code requestedAt}이 없는 것이 {@link MatchRequest}와의 차이다.
 * 둘 다 서버가 정하므로 사용자가 보낼 수 있는 값이 아니다.
 */
public record NewRequest(
        long userId,
        GameQueue queue,
        int targetSize,
        Purpose purpose,
        int playMinutes,
        VoiceMode voiceMode,
        Position primaryPosition,
        List<Position> subPositions,
        int tierOrder,
        int allowedTierMinOrder,
        int allowedTierMaxOrder
) {

    public NewRequest {
        subPositions = subPositions == null ? List.of() : List.copyOf(subPositions);
        if (!queue.allows(targetSize)) {
            throw new IllegalArgumentException(
                    "%s 큐는 %d인 파티를 만들 수 없다".formatted(queue, targetSize));
        }
        if (allowedTierMinOrder > allowedTierMaxOrder) {
            throw new IllegalArgumentException("허용 티어 범위가 뒤집혀 있다");
        }
        if (tierOrder < allowedTierMinOrder || tierOrder > allowedTierMaxOrder) {
            // 3.5는 본인 티어를 기준으로 범위를 고르게 한다. 자기가 빠진 범위는 만들 수 없다.
            throw new IllegalArgumentException("허용 티어 범위가 본인 티어를 담지 않는다");
        }
        if (playMinutes <= 0) {
            throw new IllegalArgumentException("예상 플레이시간이 있어야 한다");
        }
    }

    /** 서버가 정한 식별자와 신청 시각을 붙여 매칭이 다룰 요청으로 만든다. */
    public MatchRequest toMatchRequest(long requestId, long requestedAt) {
        return new MatchRequest(
                requestId, userId,
                queue, targetSize, purpose, playMinutes, voiceMode,
                primaryPosition, subPositions,
                tierOrder, allowedTierMinOrder, allowedTierMaxOrder,
                requestedAt);
    }
}

package com.port051.queuemate.matching.api.dto;

import com.port051.queuemate.matching.domain.GameQueue;
import com.port051.queuemate.matching.domain.Position;
import com.port051.queuemate.matching.domain.Purpose;
import com.port051.queuemate.matching.domain.VoiceMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 01 3.1 · 3.8 — 즉시 매칭 요청. 조건은 전부 필수 조건이다.
 *
 * <p>실제 아키텍처에서는 Core API가 인증된 사용자와 티어 스냅샷을 붙여 이 메모를 만든다(11.3).
 * 스파이크에는 Core API가 없으므로 {@code userId}와 {@code tierOrder}를 그대로 받는다.
 * <b>인증은 이 서비스의 책임이 아니다.</b>
 */
public record CreateMatchRequest(
        @NotNull Long userId,
        @NotNull GameQueue queue,
        @Min(2) @Max(5) int targetSize,
        @NotNull Purpose purpose,
        @Min(10) @Max(360) int playMinutes,
        @Min(1) @Max(60) int maxWaitMinutes,
        @NotNull VoiceMode voiceMode,
        @NotNull Position primaryPosition,
        List<Position> subPositions,
        Integer tierOrder,
        Integer allowedTierMinOrder,
        Integer allowedTierMaxOrder
) {
    public List<Position> subPositionsOrEmpty() {
        return subPositions == null ? List.of() : subPositions;
    }
}

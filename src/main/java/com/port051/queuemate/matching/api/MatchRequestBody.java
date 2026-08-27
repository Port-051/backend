package com.port051.queuemate.matching.api;

import com.port051.queuemate.matching.domain.GameQueue;
import com.port051.queuemate.matching.domain.Position;
import com.port051.queuemate.matching.domain.Purpose;
import com.port051.queuemate.matching.domain.VoiceMode;
import com.port051.queuemate.matching.registration.NewRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * 매칭 신청 본문.
 *
 * <p>계약 2장의 JSON에서 {@code requestId}와 {@code requestedAt}을 뺀 모양이다.
 * 둘은 서버가 정한다.
 *
 * <p>여기서 거르는 것은 <b>값 하나만 보고 판단할 수 있는 것</b>뿐이다.
 * 큐와 목표 인원의 조합, 티어 범위가 본인을 담는지 같은 규칙은 값끼리 엮여 있어
 * {@link NewRequest}가 본다.
 */
public record MatchRequestBody(

        @NotNull(message = "사용자를 알 수 없다")
        Long userId,

        @NotNull(message = "큐를 골라야 한다")
        GameQueue queue,

        @Min(value = 2, message = "파티는 두 명부터다")
        @Max(value = 5, message = "파티는 다섯 명까지다")
        int targetSize,

        @NotNull(message = "플레이 목적을 골라야 한다")
        Purpose purpose,

        @Positive(message = "예상 플레이시간이 있어야 한다")
        int playMinutes,

        @NotNull(message = "음성채팅 조건을 골라야 한다")
        VoiceMode voiceMode,

        @NotNull(message = "주 포지션을 골라야 한다")
        Position primaryPosition,

        List<Position> subPositions,

        @Positive(message = "티어가 있어야 한다")
        int tierOrder,

        @Positive(message = "허용 티어 하한이 있어야 한다")
        int allowedTierMinOrder,

        @Positive(message = "허용 티어 상한이 있어야 한다")
        int allowedTierMaxOrder
) {

    public NewRequest toNewRequest() {
        return new NewRequest(
                userId, queue, targetSize, purpose, playMinutes, voiceMode,
                primaryPosition, subPositions,
                tierOrder, allowedTierMinOrder, allowedTierMaxOrder);
    }
}

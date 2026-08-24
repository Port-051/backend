package com.port051.queuemate.matching.domain;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;

/**
 * Redis에 들어가는 "주문 메모"다. 매칭 판정에 필요한 모든 조건이 이 안에 있고,
 * 그래서 실시간 매칭은 DB를 한 번도 보지 않는다.
 *
 * <p>{@code tierOrder}는 디비전 단위의 순서값이다(0 = 아이언 IV). 티어가 아니라
 * 디비전 단위로 세는 이유는 01 3.5의 상위 구간 제약이 디비전 단위이기 때문이다.
 * 값이 {@code null}이면 언랭크·배치 중이며, 솔로·듀오 요청은 만들 수 없다(01 3.7).
 *
 * <p>{@code tierOrder}는 요청 생성 시점에 고정된 스냅샷이다(01 3.5 · 02 5.1).
 * 매칭은 이 값만 읽으므로 같은 요청 집합은 항상 같은 결과를 낸다.
 */
public record MatchRequestView(
        long requestId,
        long userId,
        GameQueue queue,
        int targetSize,
        Purpose purpose,
        int playMinutes,
        VoiceMode voiceMode,
        Position primaryPosition,
        List<Position> subPositions,
        Integer tierOrder,
        Integer allowedTierMinOrder,
        Integer allowedTierMaxOrder,
        long requestedAt,
        long expiresAt
) {

    /**
     * 01 4.3 — 결정적 FCFS의 전순서. 신청 시각이 같을 때 순서가 흔들리면
     * 같은 입력에 다른 파티가 나와 결정성을 검증할 수 없다.
     */
    public static final Comparator<MatchRequestView> FCFS =
            Comparator.comparingLong(MatchRequestView::requestedAt)
                    .thenComparingLong(MatchRequestView::requestId);

    /** 맡을 수 있는 포지션. 주 포지션이 먼저 온다(포지션 배정의 결정성 근거). */
    public SequencedSet<Position> assignablePositions() {
        LinkedHashSet<Position> positions = new LinkedHashSet<>();
        positions.add(primaryPosition);
        positions.addAll(subPositions);
        return positions;
    }

    public boolean expiredAt(long nowMillis) {
        return expiresAt <= nowMillis;
    }
}

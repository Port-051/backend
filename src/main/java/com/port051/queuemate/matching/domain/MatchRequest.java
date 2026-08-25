package com.port051.queuemate.matching.domain;

import java.util.List;

/**
 * 매칭 요청. Redis {@code req:{requestId}} 에 JSON으로 저장되는 값이다.
 *
 * <p><b>필드 이름과 타입은 05-realtime-matching-contract 2장이 고정한 것이다.</b>
 * 부하 스크립트가 이 모양에 꽂히므로 바꾸지 않는다.
 *
 * <p>티어를 이름이 아니라 순번(int)으로 들고 있는 이유는 판정에 대소 비교만 필요하기 때문이다.
 * 게임 규칙상 허용 범위는 요청을 만드는 시점에 이미 적용돼 있으므로
 * ({@code 01-functional-spec-mvp} 3.5) 매칭은 두 범위가 서로를 만족하는지만 본다.
 *
 * @param requestId           요청 식별자
 * @param userId              신청자
 * @param queue               큐 종류
 * @param targetSize          목표 파티 인원
 * @param purpose             플레이 목적
 * @param playMinutes         플레이 가능 시간(분)
 * @param voiceMode           음성채팅 조건
 * @param primaryPosition     주 포지션
 * @param subPositions        부 포지션. 없을 수 있다
 * @param tierOrder           신청자 티어 순번. 요청 생성 시점에 고정된 값이다(3.5)
 * @param allowedTierMinOrder 같이 할 티어 하한
 * @param allowedTierMaxOrder 같이 할 티어 상한
 * @param requestedAt         신청 시각(epoch millis). 4.3의 전순서에 쓴다
 */
public record MatchRequest(
        long requestId,
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
        int allowedTierMaxOrder,

        long requestedAt
) {

    public MatchRequest {
        // 매칭 루프가 후보 목록을 들고 도는 동안 값이 바뀌면 4.3의 결정성이 깨진다.
        subPositions = subPositions == null ? List.of() : List.copyOf(subPositions);
    }

    /**
     * 이 요청이 맡을 수 있는 포지션 전체. 주 포지션이 앞에 온다.
     *
     * <p>4.4가 "주 포지션 또는 부 포지션 중 하나를 배정받는다"고 정의하므로 배정기의 후보 집합이다.
     * 순서를 고정해 두는 이유는 배정 결과가 매 실행 같아야 하기 때문이다(4.3).
     */
    public List<Position> assignablePositions() {
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(primaryPosition),
                        subPositions.stream())
                .distinct()
                .toList();
    }
}

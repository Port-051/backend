package com.port051.queuemate.support;

import com.port051.queuemate.contract.MatchRequestPayload;
import com.port051.queuemate.contract.Position;
import com.port051.queuemate.contract.Purpose;
import com.port051.queuemate.contract.Queue;
import com.port051.queuemate.contract.VoiceMode;
import java.util.Arrays;
import java.util.List;

/** 테스트용 요청 만들기. 조건을 하나만 다르게 두고 나머지는 전부 통과하도록 깔아 둔다. */
public final class Requests {

    /**
     * 신청 시각의 기준점. 클래스가 로드될 때 한 번 찍는다.
     *
     * <p>고정된 과거 시각을 쓰면 최대 대기시간(01 10.2)이 지난 요청으로 판정돼
     * 매칭 루프가 명단에서 통째로 걷어낸다. 한 번만 찍어 두면 테스트 안에서는 값이 고정되므로
     * 결정성 검증에도 지장이 없다.
     */
    public static final long BASE_REQUESTED_AT = System.currentTimeMillis();

    private Requests() {}

    /** 기본값 — 자유 랭크 5인, 랭크 상승, 음성 가능, 티어 14(전 구간 허용). */
    public static MatchRequestPayload of(
            long requestId,
            long userId,
            Queue queue,
            int targetSize,
            Purpose purpose,
            VoiceMode voiceMode,
            int tierOrder,
            int allowedMin,
            int allowedMax,
            long requestedAt,
            Position primary,
            Position... subs) {
        return new MatchRequestPayload(
                requestId,
                userId,
                queue,
                targetSize,
                purpose,
                120,
                voiceMode,
                primary,
                Arrays.stream(subs).filter(p -> p != primary).distinct().toList(),
                tierOrder,
                allowedMin,
                allowedMax,
                requestedAt);
    }

    /** 포지션만 바꾼 요청. requestId가 곧 userId이고 신청 시각도 requestId 순이다. */
    public static MatchRequestPayload at(long requestId, Position primary, Position... subs) {
        return of(
                requestId,
                requestId,
                Queue.FLEX,
                5,
                Purpose.RANK_UP,
                VoiceMode.POSSIBLE,
                14,
                1,
                28,
                BASE_REQUESTED_AT + requestId,
                primary,
                subs);
    }

    /** 다섯 포지션을 전부 맡을 수 있는 요청. 01 2.4의 `상관없음`에 해당한다. */
    public static MatchRequestPayload anyPosition(long requestId) {
        return at(requestId, Position.TOP, Position.values());
    }

    /** 정원 2의 솔로·듀오 랭크 요청. */
    public static MatchRequestPayload duo(
            long requestId, int tierOrder, int allowedMin, int allowedMax, Position primary) {
        return of(
                requestId,
                requestId,
                Queue.SOLO_DUO,
                2,
                Purpose.RANK_UP,
                VoiceMode.POSSIBLE,
                tierOrder,
                allowedMin,
                allowedMax,
                BASE_REQUESTED_AT + requestId,
                primary,
                Position.values());
    }

    public static List<MatchRequestPayload> anyPositions(int count) {
        return java.util.stream.LongStream.rangeClosed(1, count)
                .mapToObj(Requests::anyPosition)
                .toList();
    }
}

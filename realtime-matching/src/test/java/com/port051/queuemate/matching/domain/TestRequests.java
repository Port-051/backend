package com.port051.queuemate.matching.domain;

import java.util.List;

/** 테스트용 요청 빌더. 판정 시나리오의 입력을 한곳에서 만든다. */
public final class TestRequests {

    public static final TierPolicy PERMISSIVE = new TierPolicy() {
        @Override
        public boolean playableTogether(GameQueue queue, Integer a, Integer b) {
            return true;
        }

        @Override
        public int allowedSpread(GameQueue queue, Integer tierOrder) {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean eligibleForSoloDuo(Integer tierOrder) {
            return tierOrder != null;
        }
    };

    private TestRequests() {
    }

    public static MatchRequestView request(long id, Position primary, Position... subs) {
        return new MatchRequestView(id, id, GameQueue.FLEX, 5, Purpose.RANK_UP, 120,
                VoiceMode.POSSIBLE, primary, List.of(subs), 14, 10, 18, 1_000L + id, 9_000_000L);
    }

    public static MatchRequestView request(long id, long userId, Position primary, VoiceMode voice,
                                           Purpose purpose, int tierOrder, int min, int max, long requestedAt) {
        return new MatchRequestView(id, userId, GameQueue.FLEX, 5, purpose, 120,
                voice, primary, List.of(), tierOrder, min, max, requestedAt, 9_000_000L);
    }
}

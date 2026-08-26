package com.port051.queuemate.matching.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 01-functional-spec-mvp 4.1 중 요청 두 개만으로 판정하는 조건들을 확인한다.
 *
 * <p>각 테스트는 {@link #base()}에서 검사하려는 필드 하나만 바꾼다.
 * 나머지가 모두 같으므로 실패하면 바꾼 필드가 원인이다.
 */
class CandidateRuleTest {

    private static MatchRequest request(
            GameQueue queue, int targetSize, Purpose purpose, int playMinutes, VoiceMode voiceMode,
            int tierOrder, int allowedTierMinOrder, int allowedTierMaxOrder) {
        return new MatchRequest(
                1L, 1L,
                queue, targetSize, purpose, playMinutes, voiceMode,
                Position.MID, List.of(),
                tierOrder, allowedTierMinOrder, allowedTierMaxOrder,
                1_755_960_000_000L);
    }

    /** 서로 후보가 되는 기준 요청. 두 개를 그대로 붙이면 모든 조건을 통과한다. */
    private static MatchRequest base() {
        return request(GameQueue.SOLO_DUO, 2, Purpose.RANK_UP, 120, VoiceMode.POSSIBLE, 14, 11, 18);
    }

    @Test
    @DisplayName("조건이 모두 같으면 후보다")
    void identicalRequestsAreCandidates() {
        assertThat(CandidateRule.allSatisfied(base(), base())).isTrue();
        assertThat(CandidateRule.unsatisfiedBy(base(), base())).isEmpty();
    }

    @Test
    @DisplayName("큐가 다르면 제외한다")
    void differentQueueIsExcluded() {
        MatchRequest other = request(GameQueue.NORMAL, 2, Purpose.RANK_UP, 120, VoiceMode.POSSIBLE, 14, 11, 18);

        assertThat(CandidateRule.unsatisfiedBy(base(), other)).containsExactly(CandidateRule.QUEUE);
    }

    @Test
    @DisplayName("목표 인원이 다르면 제외한다")
    void differentTargetSizeIsExcluded() {
        MatchRequest other = request(GameQueue.SOLO_DUO, 5, Purpose.RANK_UP, 120, VoiceMode.POSSIBLE, 14, 11, 18);

        assertThat(CandidateRule.unsatisfiedBy(base(), other)).containsExactly(CandidateRule.TARGET_SIZE);
    }

    @Test
    @DisplayName("플레이 목적이 다르면 제외한다")
    void differentPurposeIsExcluded() {
        MatchRequest other = request(GameQueue.SOLO_DUO, 2, Purpose.CASUAL, 120, VoiceMode.POSSIBLE, 14, 11, 18);

        assertThat(CandidateRule.unsatisfiedBy(base(), other)).containsExactly(CandidateRule.PURPOSE);
    }

    @Test
    @DisplayName("플레이 길이가 달라도 후보다 — 시간은 판정에 넣지 않는다")
    void playMinutesIsNotAConditionAtAll() {
        MatchRequest short_ = request(GameQueue.SOLO_DUO, 2, Purpose.RANK_UP, 30, VoiceMode.POSSIBLE, 14, 11, 18);
        MatchRequest long_ = request(GameQueue.SOLO_DUO, 2, Purpose.RANK_UP, 300, VoiceMode.POSSIBLE, 14, 11, 18);

        assertThat(CandidateRule.allSatisfied(short_, long_)).isTrue();
    }

    @Nested
    @DisplayName("음성채팅")
    class Voice {

        @Test
        @DisplayName("필수와 사용하지 않음은 제외한다")
        void requiredAgainstNotUsedIsExcluded() {
            MatchRequest required = request(GameQueue.SOLO_DUO, 2, Purpose.RANK_UP, 120, VoiceMode.REQUIRED, 14, 11, 18);
            MatchRequest notUsed = request(GameQueue.SOLO_DUO, 2, Purpose.RANK_UP, 120, VoiceMode.NOT_USED, 14, 11, 18);

            assertThat(CandidateRule.unsatisfiedBy(required, notUsed))
                    .containsExactly(CandidateRule.VOICE_MODE);
        }

        @Test
        @DisplayName("값이 달라도 호환이면 후보다")
        void differentButCompatibleIsCandidate() {
            MatchRequest required = request(GameQueue.SOLO_DUO, 2, Purpose.RANK_UP, 120, VoiceMode.REQUIRED, 14, 11, 18);
            MatchRequest possible = request(GameQueue.SOLO_DUO, 2, Purpose.RANK_UP, 120, VoiceMode.POSSIBLE, 14, 11, 18);

            assertThat(CandidateRule.allSatisfied(required, possible)).isTrue();
        }
    }

    @Nested
    @DisplayName("허용 티어 범위")
    class TierRange {

        /** 티어 14, 허용 11~18. */
        private final MatchRequest mid = base();

        @Test
        @DisplayName("양쪽이 서로를 범위에 넣으면 후보다")
        void bothAcceptEachOther() {
            MatchRequest other = request(GameQueue.SOLO_DUO, 2, Purpose.RANK_UP, 120, VoiceMode.POSSIBLE, 17, 12, 20);

            assertThat(CandidateRule.allSatisfied(mid, other)).isTrue();
        }

        @Test
        @DisplayName("한쪽만 상대를 받아들이면 제외한다")
        void oneSidedAcceptanceIsExcluded() {
            // 티어 20은 mid의 허용 상한 18을 넘는다. 반대로 상대는 mid를 받아들인다.
            MatchRequest higher = request(GameQueue.SOLO_DUO, 2, Purpose.RANK_UP, 120, VoiceMode.POSSIBLE, 20, 10, 25);

            assertThat(CandidateRule.unsatisfiedBy(mid, higher)).containsExactly(CandidateRule.TIER_RANGE);
        }

        @Test
        @DisplayName("판정은 순서를 바꿔도 같다")
        void isSymmetric() {
            MatchRequest higher = request(GameQueue.SOLO_DUO, 2, Purpose.RANK_UP, 120, VoiceMode.POSSIBLE, 20, 10, 25);

            assertThat(CandidateRule.allSatisfied(higher, mid))
                    .isEqualTo(CandidateRule.allSatisfied(mid, higher));
        }

        @Test
        @DisplayName("범위의 경계는 포함한다")
        void boundsAreInclusive() {
            MatchRequest atLowerBound = request(GameQueue.SOLO_DUO, 2, Purpose.RANK_UP, 120, VoiceMode.POSSIBLE, 11, 11, 18);
            MatchRequest atUpperBound = request(GameQueue.SOLO_DUO, 2, Purpose.RANK_UP, 120, VoiceMode.POSSIBLE, 18, 11, 18);

            assertThat(CandidateRule.allSatisfied(mid, atLowerBound)).isTrue();
            assertThat(CandidateRule.allSatisfied(mid, atUpperBound)).isTrue();
        }

        @Test
        @DisplayName("경계를 한 칸 벗어나면 제외한다")
        void justOutsideBoundsIsExcluded() {
            MatchRequest belowLowerBound = request(GameQueue.SOLO_DUO, 2, Purpose.RANK_UP, 120, VoiceMode.POSSIBLE, 10, 5, 20);

            assertThat(CandidateRule.unsatisfiedBy(mid, belowLowerBound))
                    .containsExactly(CandidateRule.TIER_RANGE);
        }
    }

    @Test
    @DisplayName("어긋난 조건이 여럿이면 전부 돌려준다")
    void reportsEveryUnsatisfiedRule() {
        MatchRequest other = request(GameQueue.NORMAL, 5, Purpose.CASUAL, 120, VoiceMode.POSSIBLE, 14, 11, 18);

        assertThat(CandidateRule.unsatisfiedBy(base(), other))
                .containsExactly(CandidateRule.QUEUE, CandidateRule.TARGET_SIZE, CandidateRule.PURPOSE);
    }

    @Test
    @DisplayName("어긋난 조건 목록은 선언 순서를 따른다 — 같은 입력이면 같은 결과다")
    void unsatisfiedRulesFollowDeclarationOrder() {
        MatchRequest other = request(GameQueue.NORMAL, 5, Purpose.CASUAL, 120, VoiceMode.NOT_USED, 99, 90, 99);
        MatchRequest required = request(GameQueue.SOLO_DUO, 2, Purpose.RANK_UP, 120, VoiceMode.REQUIRED, 14, 11, 18);

        assertThat(CandidateRule.unsatisfiedBy(required, other))
                .containsExactlyElementsOf(List.of(CandidateRule.values()));
    }

    @Test
    @DisplayName("어긋난 조건 목록은 밖에서 바꿀 수 없다")
    void unsatisfiedRulesAreImmutable() {
        List<CandidateRule> unsatisfied = CandidateRule.unsatisfiedBy(base(), base());

        assertThat(unsatisfied.getClass().getName()).startsWith("java.util.ImmutableCollections");
    }

    @Test
    @DisplayName("모든 조건에 4.5가 보여줄 이름이 있다")
    void everyRuleHasALabel() {
        for (CandidateRule rule : CandidateRule.values()) {
            assertThat(rule.label()).as("%s", rule).isNotBlank();
        }
    }
}

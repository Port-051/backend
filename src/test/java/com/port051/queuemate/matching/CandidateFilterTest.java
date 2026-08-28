package com.port051.queuemate.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.port051.queuemate.contract.MatchRequestPayload;
import com.port051.queuemate.contract.Position;
import com.port051.queuemate.contract.Purpose;
import com.port051.queuemate.contract.Queue;
import com.port051.queuemate.contract.VoiceMode;
import com.port051.queuemate.support.Requests;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 01 4.1 후보 제외 · 4.2 음성 호환. */
class CandidateFilterTest {

    /** 실제 설정값(application.yml)과 같은 밴드 표를 쓴다. */
    private final TierRule tierRule =
            new TierRule(
                    28,
                    List.of(
                            new TierRule.Band(8, 8),
                            new TierRule.Band(16, 4),
                            new TierRule.Band(24, 4),
                            new TierRule.Band(28, 3)));

    private final CandidateFilter filter = new CandidateFilter(tierRule);

    @Test
    @DisplayName("플레이 목적이 다르면 제외한다")
    void differentPurposeIsExcluded() {
        MatchRequestPayload a = Requests.at(1, Position.TOP);
        MatchRequestPayload b =
                Requests.of(2, 2, Queue.FLEX, 5, Purpose.CASUAL, VoiceMode.POSSIBLE, 14, 1, 28,
                        1_700_000_000_002L, Position.MID);

        assertThat(filter.pairCompatible(a, b)).isFalse();
    }

    @Test
    @DisplayName("같은 사용자의 요청 둘은 묶이지 않는다")
    void sameUserIsExcluded() {
        MatchRequestPayload a =
                Requests.of(1, 7, Queue.FLEX, 5, Purpose.RANK_UP, VoiceMode.POSSIBLE, 14, 1, 28,
                        1L, Position.TOP);
        MatchRequestPayload b =
                Requests.of(2, 7, Queue.FLEX, 5, Purpose.RANK_UP, VoiceMode.POSSIBLE, 14, 1, 28,
                        2L, Position.MID);

        assertThat(filter.pairCompatible(a, b)).isFalse();
    }

    @Nested
    @DisplayName("허용 티어 범위는 양쪽 모두 만족해야 한다 — 01 4.1")
    class TierRange {

        @Test
        @DisplayName("한쪽만 만족하면 제외한다")
        void oneSidedIsNotEnough() {
            // 14는 10~20을 허용해 18을 받아준다. 18은 17~19만 허용해 14를 거부한다.
            MatchRequestPayload wide = Requests.duo(1, 14, 10, 20, Position.MID);
            MatchRequestPayload narrow = Requests.duo(2, 18, 17, 19, Position.TOP);

            assertThat(wide.acceptsTier(narrow.tierOrder())).isTrue();
            assertThat(narrow.acceptsTier(wide.tierOrder())).isFalse();
            assertThat(filter.pairCompatible(wide, narrow)).isFalse();
        }

        @Test
        @DisplayName("양쪽 다 만족하면 통과한다")
        void bothSidesPass() {
            MatchRequestPayload a = Requests.duo(1, 14, 12, 16, Position.MID);
            MatchRequestPayload b = Requests.duo(2, 15, 13, 17, Position.TOP);

            assertThat(filter.pairCompatible(a, b)).isTrue();
        }
    }

    @Nested
    @DisplayName("게임 규칙상 티어 제약 — 01 3.5")
    class GameTierRule {

        @Test
        @DisplayName("솔로·듀오 랭크는 밴드가 허용하는 폭을 넘으면 제외한다")
        void soloDuoRespectsBand() {
            // 둘 다 서로를 전 구간 허용하지만, 실버(9~16) 밴드의 허용 폭은 4다.
            MatchRequestPayload low = Requests.duo(1, 10, 1, 28, Position.MID);
            MatchRequestPayload high = Requests.duo(2, 20, 1, 28, Position.TOP);

            assertThat(filter.pairCompatible(low, high)).isFalse();
        }

        @Test
        @DisplayName("자유 랭크는 게임 규칙상 티어 제약을 받지 않는다")
        void flexIgnoresBand() {
            MatchRequestPayload low =
                    Requests.of(1, 1, Queue.FLEX, 2, Purpose.RANK_UP, VoiceMode.POSSIBLE, 3, 1, 31,
                            1L, Position.MID, Position.values());
            MatchRequestPayload high =
                    Requests.of(2, 2, Queue.FLEX, 2, Purpose.RANK_UP, VoiceMode.POSSIBLE, 30, 1, 31,
                            2L, Position.TOP, Position.values());

            assertThat(filter.pairCompatible(low, high)).isTrue();
        }

        @Test
        @DisplayName("마스터 이상은 솔로·듀오 랭크 요청을 만들 수 없다 — 01 3.6")
        void masterCannotSoloDuo() {
            assertThat(tierRule.canCreateSoloDuoRequest(28)).isTrue();
            assertThat(tierRule.canCreateSoloDuoRequest(29)).isFalse(); // 마스터
            assertThat(tierRule.canCreateSoloDuoRequest(31)).isFalse(); // 챌린저
        }
    }

    @Nested
    @DisplayName("음성채팅 호환 — 01 4.2")
    class Voice {

        @Test
        @DisplayName("필수와 사용안함만 서로 막힌다")
        void onlyRequiredAndNoneClash() {
            assertThat(VoiceCompatibility.compatible(VoiceMode.REQUIRED, VoiceMode.NONE)).isFalse();
            assertThat(VoiceCompatibility.compatible(VoiceMode.NONE, VoiceMode.REQUIRED)).isFalse();

            assertThat(VoiceCompatibility.compatible(VoiceMode.REQUIRED, VoiceMode.REQUIRED)).isTrue();
            assertThat(VoiceCompatibility.compatible(VoiceMode.REQUIRED, VoiceMode.POSSIBLE)).isTrue();
            assertThat(VoiceCompatibility.compatible(VoiceMode.POSSIBLE, VoiceMode.NONE)).isTrue();
            assertThat(VoiceCompatibility.compatible(VoiceMode.NONE, VoiceMode.NONE)).isTrue();
        }

        @Test
        @DisplayName("파티에 필수가 한 명이라도 있으면 사용안함을 넣지 않는다")
        void partyRuleMatchesPairwise() {
            assertThat(
                            VoiceCompatibility.partyCompatible(
                                    List.of(VoiceMode.REQUIRED, VoiceMode.POSSIBLE, VoiceMode.NONE)))
                    .isFalse();
            assertThat(
                            VoiceCompatibility.partyCompatible(
                                    List.of(VoiceMode.POSSIBLE, VoiceMode.POSSIBLE, VoiceMode.NONE)))
                    .isTrue();
        }

        @Test
        @DisplayName("필수가 한 명이라도 있으면 음성 파티다")
        void voicePartyFlag() {
            assertThat(VoiceCompatibility.isVoiceParty(List.of(VoiceMode.POSSIBLE, VoiceMode.REQUIRED)))
                    .isTrue();
            assertThat(VoiceCompatibility.isVoiceParty(List.of(VoiceMode.POSSIBLE, VoiceMode.NONE)))
                    .isFalse();
        }
    }
}

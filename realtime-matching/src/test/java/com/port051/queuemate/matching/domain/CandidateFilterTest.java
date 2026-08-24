package com.port051.queuemate.matching.domain;

import static com.port051.queuemate.matching.domain.TestRequests.PERMISSIVE;
import static com.port051.queuemate.matching.domain.TestRequests.request;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CandidateFilterTest {

    private final CandidateFilter filter = new CandidateFilter(PERMISSIVE);

    @Test
    @DisplayName("01 4.1 — 허용 티어 범위는 양쪽 모두 만족해야 한다")
    void tierRangeMustBeMutual() {
        MatchRequestView low = request(1, 1, Position.TOP, VoiceMode.POSSIBLE, Purpose.RANK_UP, 10, 8, 20, 100);
        MatchRequestView high = request(2, 2, Position.MID, VoiceMode.POSSIBLE, Purpose.RANK_UP, 18, 16, 20, 200);

        // low는 high(18)를 받아들이지만, high는 low(10)를 받아들이지 않는다
        assertThat(filter.compatible(low, high)).isFalse();
        assertThat(filter.compatible(high, low)).isFalse();
    }

    @Test
    @DisplayName("01 4.2 — 음성 '필수'와 '사용하지 않음'은 붙지 않는다")
    void voiceIncompatible() {
        MatchRequestView required = request(1, 1, Position.TOP, VoiceMode.REQUIRED, Purpose.RANK_UP, 14, 10, 18, 100);
        MatchRequestView none = request(2, 2, Position.MID, VoiceMode.NONE, Purpose.RANK_UP, 14, 10, 18, 200);
        MatchRequestView possible = request(3, 3, Position.JUNGLE, VoiceMode.POSSIBLE, Purpose.RANK_UP, 14, 10, 18, 300);

        assertThat(filter.compatible(required, none)).isFalse();
        assertThat(filter.compatible(required, possible)).isTrue();
        assertThat(filter.compatible(none, possible)).isTrue();
    }

    @Test
    @DisplayName("01 4.1 — 플레이 목적이 다르면 후보에서 제외한다")
    void purposeMustMatch() {
        MatchRequestView rankUp = request(1, 1, Position.TOP, VoiceMode.POSSIBLE, Purpose.RANK_UP, 14, 10, 18, 100);
        MatchRequestView casual = request(2, 2, Position.MID, VoiceMode.POSSIBLE, Purpose.CASUAL, 14, 10, 18, 200);

        assertThat(filter.compatible(rankUp, casual)).isFalse();
    }

    @Test
    @DisplayName("같은 사람의 두 요청은 서로 후보가 되지 않는다")
    void sameUserExcluded() {
        MatchRequestView first = request(1, 7, Position.TOP, VoiceMode.POSSIBLE, Purpose.RANK_UP, 14, 10, 18, 100);
        MatchRequestView second = request(2, 7, Position.MID, VoiceMode.POSSIBLE, Purpose.RANK_UP, 14, 10, 18, 200);

        assertThat(filter.compatible(first, second)).isFalse();
    }
}

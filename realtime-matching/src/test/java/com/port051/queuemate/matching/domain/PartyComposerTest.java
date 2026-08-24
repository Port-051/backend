package com.port051.queuemate.matching.domain;

import static com.port051.queuemate.matching.domain.TestRequests.PERMISSIVE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PartyComposerTest {

    private final PartyComposer composer = new PartyComposer(new CandidateFilter(PERMISSIVE), 20_000);

    private static MatchRequestView request(long id, Position primary, long requestedAt) {
        return new MatchRequestView(id, id, GameQueue.FLEX, 5, Purpose.RANK_UP, 120,
                VoiceMode.POSSIBLE, primary, List.of(Position.TOP, Position.MID, Position.SUPPORT),
                14, 10, 18, requestedAt, 9_000_000L);
    }

    private static List<MatchRequestView> fivePlusTwo() {
        return new ArrayList<>(List.of(
                request(1, Position.TOP, 100),
                request(2, Position.JUNGLE, 200),
                request(3, Position.MID, 300),
                request(4, Position.BOTTOM, 400),
                request(5, Position.SUPPORT, 500),
                request(6, Position.TOP, 600),
                request(7, Position.JUNGLE, 700)));
    }

    @Test
    @DisplayName("01 4.3 — 가장 앞선 요청을 기준으로 정원을 채운다")
    void composesFromEarliestRequest() {
        Optional<ComposedParty> party = composer.compose(fivePlusTwo(), combo -> true);

        assertThat(party).isPresent();
        assertThat(party.get().combo()).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(party.get().positions().byRequestId().values()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("01 4.3 — 같은 요청 집합이면 입력 순서가 흔들려도 같은 파티가 나온다")
    void deterministicRegardlessOfInputOrder() {
        List<MatchRequestView> pool = fivePlusTwo();
        List<MatchRequestView> shuffled = new ArrayList<>(pool);
        shuffled.sort(Comparator.comparingLong(MatchRequestView::requestId).reversed());
        // 호출자는 항상 FCFS로 정렬해서 넘긴다 — 그 계약이 결정성의 근거다
        shuffled.sort(MatchRequestView.FCFS);

        assertThat(composer.compose(shuffled, combo -> true).orElseThrow().combo())
                .isEqualTo(composer.compose(pool, combo -> true).orElseThrow().combo());
    }

    @Test
    @DisplayName("01 5.4 — 거절로 억제된 조합은 건너뛰고 다른 조합을 찾는다")
    void skipsSuppressedCombo() {
        Set<Long> suppressed = Set.of(1L, 2L, 3L, 4L, 5L);

        Optional<ComposedParty> party = composer.compose(fivePlusTwo(), combo -> !combo.equals(suppressed));

        assertThat(party).isPresent();
        assertThat(party.get().combo()).isNotEqualTo(suppressed);
        assertThat(party.get().combo()).hasSize(5);
    }

    @Test
    @DisplayName("정원을 못 채우면 파티를 만들지 않는다 — 조건을 완화하지 않는다")
    void noPartyWhenShort() {
        List<MatchRequestView> pool = List.of(
                request(1, Position.TOP, 100),
                request(2, Position.JUNGLE, 200));

        assertThat(composer.compose(pool, combo -> true)).isEmpty();
    }
}

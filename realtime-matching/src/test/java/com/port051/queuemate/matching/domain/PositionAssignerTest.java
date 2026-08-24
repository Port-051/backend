package com.port051.queuemate.matching.domain;

import static com.port051.queuemate.matching.domain.TestRequests.request;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PositionAssignerTest {

    @Test
    @DisplayName("01 4.4 — 5인 파티는 다섯 포지션을 각각 한 명씩 채운다")
    void assignsFiveDistinctPositions() {
        List<MatchRequestView> members = List.of(
                request(1, Position.TOP),
                request(2, Position.JUNGLE),
                request(3, Position.MID),
                request(4, Position.BOTTOM),
                request(5, Position.SUPPORT));

        PositionAssignment assignment = PositionAssigner.assign(members).orElseThrow();

        assertThat(assignment.byRequestId()).hasSize(5);
        assertThat(assignment.byRequestId().values()).doesNotHaveDuplicates();
        assertThat(assignment.primaryCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("주 포지션이 겹치면 부 포지션으로 물러난다")
    void fallsBackToSubPosition() {
        List<MatchRequestView> members = List.of(
                request(1, Position.MID),
                request(2, Position.MID, Position.TOP));

        PositionAssignment assignment = PositionAssigner.assign(members).orElseThrow();

        assertThat(assignment.byRequestId().get(1L)).isEqualTo(Position.MID);
        assertThat(assignment.byRequestId().get(2L)).isEqualTo(Position.TOP);
        assertThat(assignment.primaryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("주 포지션을 많이 살리는 배정을 고른다")
    void prefersMorePrimaries() {
        List<MatchRequestView> members = List.of(
                request(1, Position.TOP, Position.MID),
                request(2, Position.MID, Position.TOP));

        PositionAssignment assignment = PositionAssigner.assign(members).orElseThrow();

        assertThat(assignment.primaryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("01 4.4 — 배정이 불가능한 조합은 파티를 성립시키지 않는다")
    void rejectsInfeasibleCombination() {
        List<MatchRequestView> members = List.of(
                request(1, Position.SUPPORT),
                request(2, Position.SUPPORT),
                request(3, Position.SUPPORT));

        Optional<PositionAssignment> assignment = PositionAssigner.assign(members);

        assertThat(assignment).isEmpty();
    }
}

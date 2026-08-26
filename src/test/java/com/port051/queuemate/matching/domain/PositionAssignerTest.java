package com.port051.queuemate.matching.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 01-functional-spec-mvp 4.4 포지션 배정. */
class PositionAssignerTest {

    private static long nextRequestId = 1L;

    /** 배정에 쓰이는 것은 포지션뿐이므로 나머지 필드는 전부 같게 둔다. */
    private static MatchRequest member(Position primary, Position... subs) {
        return new MatchRequest(
                nextRequestId++, nextRequestId,
                GameQueue.SOLO_DUO, 5, Purpose.RANK_UP, 120, VoiceMode.POSSIBLE,
                primary, List.of(subs),
                14, 11, 18,
                1_755_960_000_000L);
    }

    @Test
    @DisplayName("주 포지션이 서로 다르면 전원이 주 포지션을 받는다")
    void everyoneGetsTheirPrimaryWhenNoneCollide() {
        List<MatchRequest> party = List.of(
                member(Position.TOP),
                member(Position.JUNGLE),
                member(Position.MID),
                member(Position.BOTTOM),
                member(Position.SUPPORT));

        PositionAssignment assignment = PositionAssigner.assign(party).orElseThrow();

        assertThat(assignment.positions()).containsExactly(
                Position.TOP, Position.JUNGLE, Position.MID, Position.BOTTOM, Position.SUPPORT);
        assertThat(assignment.primaryCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("5인 파티는 다섯 포지션이 한 명씩 채워진다")
    void fivePlayersFillEveryPosition() {
        List<MatchRequest> party = List.of(
                member(Position.MID, Position.TOP),
                member(Position.MID, Position.JUNGLE),
                member(Position.MID, Position.BOTTOM),
                member(Position.MID, Position.SUPPORT),
                member(Position.MID));

        PositionAssignment assignment = PositionAssigner.assign(party).orElseThrow();

        assertThat(assignment.positions())
                .containsExactlyInAnyOrder(Position.values())
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("주 포지션이 겹치면 부 포지션으로 밀린다")
    void fallsBackToSubPosition() {
        List<MatchRequest> party = List.of(
                member(Position.MID, Position.TOP),
                member(Position.MID));

        PositionAssignment assignment = PositionAssigner.assign(party).orElseThrow();

        // 두 번째 참가자는 MID 말고 앉을 자리가 없으므로 첫 번째가 물러난다.
        assertThat(assignment.positions()).containsExactly(Position.TOP, Position.MID);
        assertThat(assignment.primaryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("먼저 찾은 배정보다 주 포지션이 많은 배정이 있으면 그쪽을 고른다")
    void prefersTheAssignmentWithMorePrimaries() {
        List<MatchRequest> party = List.of(
                member(Position.MID, Position.SUPPORT),
                member(Position.MID, Position.TOP),
                member(Position.TOP, Position.JUNGLE));

        PositionAssignment assignment = PositionAssigner.assign(party).orElseThrow();

        // 첫 참가자에게 주 포지션 MID를 주면 뒤의 둘이 모두 부 포지션으로 밀려 1명뿐이다.
        // 첫 참가자가 SUPPORT로 물러나면 뒤의 둘이 주 포지션을 받아 2명이 된다.
        assertThat(assignment.primaryCount()).isEqualTo(2);
        assertThat(assignment.positions())
                .containsExactly(Position.SUPPORT, Position.MID, Position.TOP);
    }

    @Nested
    @DisplayName("배정 불가")
    class Impossible {

        @Test
        @DisplayName("앉을 자리가 겹치기만 하면 파티가 성립하지 않는다")
        void noRoomForEveryone() {
            List<MatchRequest> party = List.of(
                    member(Position.MID, Position.TOP),
                    member(Position.MID, Position.TOP),
                    member(Position.MID, Position.TOP));

            assertThat(PositionAssigner.assign(party)).isEmpty();
        }

        @Test
        @DisplayName("포지션 수보다 사람이 많으면 성립하지 않는다")
        void moreMembersThanPositions() {
            List<MatchRequest> party = List.of(
                    member(Position.TOP, Position.values()),
                    member(Position.JUNGLE, Position.values()),
                    member(Position.MID, Position.values()),
                    member(Position.BOTTOM, Position.values()),
                    member(Position.SUPPORT, Position.values()),
                    member(Position.TOP, Position.values()));

            assertThat(PositionAssigner.assign(party)).isEmpty();
        }

        @Test
        @DisplayName("일부만 앉힌 결과는 돌려주지 않는다")
        void neverReturnsAPartialAssignment() {
            List<MatchRequest> party = List.of(
                    member(Position.MID, Position.TOP),
                    member(Position.MID),
                    member(Position.TOP));

            assertThat(PositionAssigner.assign(party)).isEmpty();
        }
    }

    @Nested
    @DisplayName("2~4인")
    class SmallParty {

        @Test
        @DisplayName("겹치지 않기만 하면 되고 특정 포지션을 요구하지 않는다")
        void onlyNeedsDistinctPositions() {
            List<MatchRequest> party = List.of(
                    member(Position.TOP),
                    member(Position.JUNGLE),
                    member(Position.MID));

            PositionAssignment assignment = PositionAssigner.assign(party).orElseThrow();

            assertThat(assignment.positions()).doesNotHaveDuplicates();
            assertThat(assignment.size()).isEqualTo(3);
        }

        @Test
        @DisplayName("혼자면 항상 주 포지션을 받는다")
        void aSingleMemberAlwaysGetsTheirPrimary() {
            PositionAssignment assignment = PositionAssigner.assign(List.of(member(Position.SUPPORT))).orElseThrow();

            assertThat(assignment.positions()).containsExactly(Position.SUPPORT);
            assertThat(assignment.primaryCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("같은 입력에는 항상 같은 배정이 나온다")
    void isDeterministic() {
        List<MatchRequest> party = List.of(
                member(Position.MID, Position.TOP, Position.JUNGLE),
                member(Position.MID, Position.JUNGLE, Position.BOTTOM),
                member(Position.TOP, Position.MID, Position.SUPPORT),
                member(Position.JUNGLE, Position.TOP, Position.BOTTOM));

        PositionAssignment first = PositionAssigner.assign(party).orElseThrow();

        for (int i = 0; i < 20; i++) {
            assertThat(PositionAssigner.assign(party).orElseThrow()).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("배정 결과는 밖에서 바꿀 수 없다")
    void assignmentIsImmutable() {
        PositionAssignment assignment = PositionAssigner.assign(List.of(member(Position.MID))).orElseThrow();

        assertThat(assignment.positions().getClass().getName()).startsWith("java.util.ImmutableCollections");
    }

    @Test
    @DisplayName("참가자가 없으면 빈 배정이다")
    void emptyPartyIsTriviallyAssignable() {
        Optional<PositionAssignment> assignment = PositionAssigner.assign(List.of());

        assertThat(assignment).isPresent();
        assertThat(assignment.orElseThrow().positions()).isEmpty();
    }
}

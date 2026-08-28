package com.port051.queuemate.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.port051.queuemate.contract.MatchRequestPayload;
import com.port051.queuemate.contract.Position;
import com.port051.queuemate.support.Requests;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 01 4.4 포지션 배정. */
class PositionAssignerTest {

    @Test
    @DisplayName("5인 파티는 다섯 포지션을 각각 한 명씩 채운다")
    void fivePlayersFillFivePositions() {
        List<MatchRequestPayload> party =
                List.of(
                        Requests.at(1, Position.TOP),
                        Requests.at(2, Position.JUNGLE),
                        Requests.at(3, Position.MID),
                        Requests.at(4, Position.BOTTOM),
                        Requests.at(5, Position.SUPPORT));

        Optional<PositionAssignment> result = PositionAssigner.assign(party);

        assertThat(result).isPresent();
        assertThat(result.get().byRequestId().values()).containsExactlyInAnyOrder(Position.values());
        assertThat(result.get().primaryCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("주 포지션이 겹치면 부 포지션으로 밀어서 배정한다")
    void fallsBackToSubPosition() {
        List<MatchRequestPayload> party =
                List.of(
                        Requests.at(1, Position.MID),
                        Requests.at(2, Position.MID, Position.TOP));

        Optional<PositionAssignment> result = PositionAssigner.assign(party);

        assertThat(result).isPresent();
        assertThat(result.get().byRequestId()).containsEntry(1L, Position.MID);
        assertThat(result.get().byRequestId()).containsEntry(2L, Position.TOP);
        // 한 명만 주 포지션을 받았다. 배정 가능 여부가 통과 기준이고 주 포지션 수는 선호다.
        assertThat(result.get().primaryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("주 포지션을 받는 사람이 최대가 되는 배정을 고른다")
    void maximisesPrimaryAssignments() {
        // 1번은 미드만, 2번은 미드·탑, 3번은 탑·정글.
        // 탐욕적으로 앞에서부터 주고 되돌아가지 않으면 2번이 탑을 가져가 3번이 정글로 밀린다(주=1).
        // 최적은 1=미드, 2=탑, 3=정글이 아니라 — 2번의 주가 미드라 어쩔 수 없다.
        // 대신 2번과 3번의 순서를 바꾸면 결과가 달라지는지가 핵심이다.
        List<MatchRequestPayload> party =
                List.of(
                        Requests.at(1, Position.MID),
                        Requests.at(2, Position.MID, Position.TOP),
                        Requests.at(3, Position.TOP, Position.JUNGLE));

        PositionAssignment result = PositionAssigner.assign(party).orElseThrow();

        assertThat(result.size()).isEqualTo(3);
        assertThat(result.byRequestId().values()).doesNotHaveDuplicates();
        assertThat(result.byRequestId()).containsEntry(1L, Position.MID);
    }

    @Test
    @DisplayName("배정이 불가능한 조합은 파티를 성립시키지 않는다")
    void rejectsInfeasibleCombination() {
        // 셋 다 미드만 할 수 있다. 포지션이 겹치지 않게 나눌 방법이 없다.
        List<MatchRequestPayload> party =
                List.of(
                        Requests.at(1, Position.MID),
                        Requests.at(2, Position.MID),
                        Requests.at(3, Position.MID));

        assertThat(PositionAssigner.feasible(party)).isFalse();
        assertThat(PositionAssigner.assign(party)).isEmpty();
    }

    @Test
    @DisplayName("여섯 명은 포지션이 다섯뿐이라 비둘기집으로 불가능하다")
    void sixPlayersNeverFit() {
        List<MatchRequestPayload> party =
                List.of(
                        Requests.anyPosition(1),
                        Requests.anyPosition(2),
                        Requests.anyPosition(3),
                        Requests.anyPosition(4),
                        Requests.anyPosition(5),
                        Requests.anyPosition(6));

        assertThat(PositionAssigner.feasible(party)).isFalse();
    }

    @Test
    @DisplayName("같은 입력에 항상 같은 배정이 나온다 — 01 4.3 결정성")
    void isDeterministic() {
        List<MatchRequestPayload> party =
                List.of(
                        Requests.at(1, Position.MID, Position.TOP, Position.JUNGLE),
                        Requests.at(2, Position.MID, Position.TOP),
                        Requests.at(3, Position.TOP, Position.JUNGLE, Position.MID));

        PositionAssignment first = PositionAssigner.assign(party).orElseThrow();
        for (int i = 0; i < 50; i++) {
            assertThat(PositionAssigner.assign(party).orElseThrow().byRequestId())
                    .isEqualTo(first.byRequestId());
        }
    }
}

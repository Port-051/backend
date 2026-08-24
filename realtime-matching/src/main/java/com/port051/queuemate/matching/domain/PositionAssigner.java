package com.port051.queuemate.matching.domain;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * 01 4.4 — 한 파티 안에서 두 사람이 같은 포지션을 맡지 않게 배정한다.
 * 5인 파티는 다섯 포지션이 서로 달라야 하므로 "겹치지 않음"이 곧 "다섯 자리 모두 채움"이다.
 *
 * <p>지금은 백트래킹 완전 탐색이다(5명이면 최대 5! = 120). README 8단계의 가지치기는
 * 예약 배치 처리량(02 6.4)이 걸릴 때 들어온다. 여기서 미리 최적화하지 않는다.
 *
 * <p>결정성: 멤버는 FCFS 순서대로, 포지션은 주 포지션 먼저 그다음 부 포지션 선언 순서로 훑고,
 * 동점인 후보 배정은 <b>먼저 찾은 것을 유지</b>한다. 같은 입력이면 항상 같은 배정이 나온다.
 */
public final class PositionAssigner {

    private PositionAssigner() {
    }

    public static Optional<PositionAssignment> assign(List<MatchRequestView> members) {
        Best best = new Best();
        search(members, 0, new LinkedHashMap<>(), EnumSet.noneOf(Position.class), 0, best);
        return Optional.ofNullable(best.value);
    }

    public static boolean feasible(List<MatchRequestView> members) {
        return assign(members).isPresent();
    }

    private static void search(List<MatchRequestView> members,
                               int index,
                               LinkedHashMap<Long, Position> chosen,
                               EnumSet<Position> used,
                               int primaryCount,
                               Best best) {
        if (index == members.size()) {
            if (best.value == null || primaryCount > best.value.primaryCount()) {
                best.value = new PositionAssignment(new LinkedHashMap<>(chosen), primaryCount);
            }
            return;
        }
        MatchRequestView member = members.get(index);
        for (Position position : member.assignablePositions()) {
            if (used.contains(position)) {
                continue;
            }
            used.add(position);
            chosen.put(member.requestId(), position);
            search(members, index + 1, chosen, used,
                    primaryCount + (position == member.primaryPosition() ? 1 : 0), best);
            chosen.remove(member.requestId());
            used.remove(position);
        }
    }

    private static final class Best {
        private PositionAssignment value;
    }
}

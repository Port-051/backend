package com.port051.queuemate.matching.domain;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * 파티 참가자에게 포지션을 배정한다. 01-functional-spec-mvp 4.4.
 *
 * <p>4.4는 두 가지를 구분한다. <b>배정 가능 여부는 통과·탈락 기준</b>이고,
 * 주 포지션을 몇 명이 받았는지는 <b>같은 조합 안에서 더 나은 배정을 고르는 기준</b>이다.
 * 그래서 {@link #assign}은 배정이 불가능하면 비어 있는 결과를 돌려주고,
 * 가능하면 주 포지션 수가 가장 많은 배정 하나를 돌려준다.
 *
 * <p>5인 파티에 다섯 포지션을 각각 한 명씩 채우라는 규칙은 따로 검사하지 않는다.
 * 사람이 다섯이고 포지션이 다섯인데 서로 겹치지 않게 배정했다면 다섯 자리가 모두 찬 것이므로,
 * 겹치지 않는다는 조건 하나에 이미 들어 있다.
 *
 * <p>탐색은 완전탐색이다. 파티는 최대 다섯 명이고 각자 고를 수 있는 자리도 최대 다섯이라
 * 경우의 수가 작고, 무엇보다 <b>결정성(4.3)</b>을 눈으로 확인할 수 있다.
 * 참가자를 넘겨받은 순서대로 훑고 각자의 {@link MatchRequest#assignablePositions()} 순서대로
 * 자리를 시도하므로, 같은 입력에는 항상 같은 배정이 나온다. 주 포지션 수가 같은 배정이 여럿이면
 * 그중 먼저 찾은 것을 쓴다.
 */
public final class PositionAssigner {

    private PositionAssigner() {
    }

    /**
     * 참가자 전원에게 겹치지 않는 포지션을 배정한다.
     *
     * <p>한 명이라도 앉힐 자리가 없으면 배정 자체가 실패한다. 4.4가 "배정이 불가능한 후보 조합은
     * 파티를 성립시키지 않는다"고 정했으므로 일부만 배정한 결과는 돌려주지 않는다.
     *
     * @param members 배정할 참가자. 넘긴 순서가 결과 순서다
     * @return 배정 결과. 전원을 앉힐 수 없으면 빈 값
     */
    public static Optional<PositionAssignment> assign(List<MatchRequest> members) {
        if (members.size() > Position.values().length) {
            return Optional.empty();
        }
        Search search = new Search(members);
        search.explore(0, EnumSet.noneOf(Position.class), new Position[members.size()], 0);
        return search.best;
    }

    /** 완전탐색의 상태. 가장 좋은 배정 하나만 들고 있는다. */
    private static final class Search {

        private final int size;
        private final List<Position> primaries;
        private final List<List<Position>> options;

        private Optional<PositionAssignment> best = Optional.empty();
        private int bestPrimaryCount = -1;

        Search(List<MatchRequest> members) {
            this.size = members.size();
            this.primaries = new ArrayList<>(members.size());
            this.options = new ArrayList<>(members.size());
            for (MatchRequest member : members) {
                // assignablePositions()는 호출할 때마다 목록을 새로 만든다. 탐색 안쪽에서 부르면
                // 같은 참가자의 목록을 가지가 갈릴 때마다 다시 만들게 되므로 한 번만 꺼내 둔다.
                primaries.add(member.primaryPosition());
                options.add(member.assignablePositions());
            }
        }

        void explore(int index, EnumSet<Position> taken, Position[] chosen, int primaryCount) {
            if (bestPrimaryCount == size) {
                // 전원이 주 포지션을 받았다. 더 좋아질 수 없다.
                return;
            }
            if (index == size) {
                if (primaryCount > bestPrimaryCount) {
                    bestPrimaryCount = primaryCount;
                    best = Optional.of(new PositionAssignment(List.of(chosen), primaryCount));
                }
                return;
            }
            for (Position position : options.get(index)) {
                if (taken.contains(position)) {
                    continue;
                }
                taken.add(position);
                chosen[index] = position;
                explore(index + 1, taken, chosen,
                        position == primaries.get(index) ? primaryCount + 1 : primaryCount);
                taken.remove(position);
            }
            chosen[index] = null;
        }
    }
}

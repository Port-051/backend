package com.port051.queuemate.matching;

import com.port051.queuemate.contract.MatchRequestPayload;
import com.port051.queuemate.contract.Position;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 포지션 배정. 01 4.4.
 *
 * <p>규칙은 셋이다.
 * <ul>
 *   <li>한 파티 안에서 두 사람이 같은 포지션을 맡지 않는다 — 이게 INV-4다</li>
 *   <li>각 참가자는 주 또는 부 포지션 중 하나를 받는다</li>
 *   <li>주 포지션을 받은 사람이 많을수록 좋은 배정이되, <b>배정 가능 여부 자체는 통과·탈락 기준이다</b></li>
 * </ul>
 *
 * <p><b>왜 두 알고리즘인가.</b> 매칭 루프는 후보를 하나씩 얹어보며 파티를 채우므로
 * "이 조합이 배정 가능한가"를 조합마다 묻는다. 이 질문은 {@link #feasible} 쪽 —
 * 이분 매칭(Kuhn) O(V·E) — 으로 싸게 끊는다. 실제로 어느 포지션을 줄지는 파티가 성립한 뒤
 * 한 번만 필요하므로, 그때만 {@link #assign}의 완전 탐색으로 <b>주 포지션 수를 최대화</b>한다.
 *
 * <p>완전 탐색이 허용되는 이유는 파티 정원이 최대 5이고 포지션도 5라 경우의 수가
 * 5·4·3·2·1 = 120을 넘지 않기 때문이다. 근사 없이 최적해가 나온다.
 *
 * <p><b>결정성.</b> 두 함수 모두 입력 순서와 {@link Position} 선언 순서만 보고 움직인다.
 * 같은 후보 목록을 넣으면 항상 같은 배정이 나오므로 01 4.3의 결정성을 깨지 않는다.
 */
public final class PositionAssigner {

    private PositionAssigner() {}

    /**
     * 이 조합에 포지션을 겹치지 않게 나눠줄 수 있는가.
     *
     * <p>사람 수가 포지션 수(5)를 넘으면 비둘기집으로 즉시 불가능하다.
     * 그 외에는 이분 그래프(사람 ↔ 포지션)의 최대 매칭이 인원 수와 같은지 본다.
     */
    public static boolean feasible(List<MatchRequestPayload> members) {
        int n = members.size();
        if (n == 0) return true;
        if (n > Position.COUNT) return false;

        List<Set<Position>> playable = new ArrayList<>(n);
        for (MatchRequestPayload m : members) {
            Set<Position> options = m.playablePositions();
            if (options.isEmpty()) return false;
            playable.add(options);
        }

        // 포지션 → 그 자리를 차지한 사람의 인덱스. -1이면 비어 있다.
        Map<Position, Integer> takenBy = new EnumMap<>(Position.class);
        int matched = 0;
        for (int i = 0; i < n; i++) {
            boolean[] visited = new boolean[Position.values().length];
            if (augment(i, playable, takenBy, visited)) matched++;
        }
        return matched == n;
    }

    /** Kuhn 증대 경로 — 사람 {@code i}에게 자리를 만들어 줄 수 있으면 true. */
    private static boolean augment(
            int i, List<Set<Position>> playable, Map<Position, Integer> takenBy, boolean[] visited) {
        for (Position position : Position.values()) {
            if (!playable.get(i).contains(position) || visited[position.ordinal()]) continue;
            visited[position.ordinal()] = true;
            Integer holder = takenBy.get(position);
            if (holder == null || augment(holder, playable, takenBy, visited)) {
                takenBy.put(position, i);
                return true;
            }
        }
        return false;
    }

    /**
     * 실제 배정. 주 포지션을 받는 사람 수가 최대인 조합을 고른다.
     *
     * <p>동점이면 먼저 찾은 것이 이긴다. 사람은 입력 순서로, 포지션은
     * <b>주 포지션 먼저 그다음 선언 순서</b>로 훑으므로 그 "먼저"가 매번 같다.
     *
     * @return 배정 불가능하면 {@link Optional#empty()} — 파티를 성립시키지 않는다(01 4.4)
     */
    public static Optional<PositionAssignment> assign(List<MatchRequestPayload> members) {
        int n = members.size();
        if (n == 0) return Optional.of(new PositionAssignment(Map.of(), 0));
        if (n > Position.COUNT) return Optional.empty();

        List<List<Position>> options = new ArrayList<>(n);
        for (MatchRequestPayload m : members) {
            List<Position> ordered = new ArrayList<>();
            ordered.add(m.primaryPosition()); // 주 포지션을 먼저 시도한다
            for (Position p : Position.values()) {
                if (p != m.primaryPosition() && m.subPositions().contains(p)) ordered.add(p);
            }
            if (ordered.isEmpty()) return Optional.empty();
            options.add(ordered);
        }

        Search search = new Search(members, options);
        search.walk(0, new EnumMap<>(Position.class), 0);
        return search.best == null
                ? Optional.empty()
                : Optional.of(new PositionAssignment(search.best, search.bestPrimaryCount));
    }

    /** 가지치기가 붙은 완전 탐색. 남은 인원을 전부 주 포지션에 넣어도 최고 기록을 못 넘으면 접는다. */
    private static final class Search {
        private final List<MatchRequestPayload> members;
        private final List<List<Position>> options;
        private Map<Long, Position> best;
        private int bestPrimaryCount = -1;

        Search(List<MatchRequestPayload> members, List<List<Position>> options) {
            this.members = members;
            this.options = options;
        }

        void walk(int index, Map<Position, Long> taken, int primaryCount) {
            int remaining = members.size() - index;
            if (primaryCount + remaining <= bestPrimaryCount) return; // 최고 기록을 못 넘는다

            if (index == members.size()) {
                Map<Long, Position> assignment = new HashMap<>();
                taken.forEach((position, requestId) -> assignment.put(requestId, position));
                best = assignment;
                bestPrimaryCount = primaryCount;
                return;
            }

            MatchRequestPayload member = members.get(index);
            for (Position position : options.get(index)) {
                if (taken.containsKey(position)) continue;
                taken.put(position, member.requestId());
                boolean isPrimary = position == member.primaryPosition();
                walk(index + 1, taken, primaryCount + (isPrimary ? 1 : 0));
                taken.remove(position);
            }
        }
    }
}

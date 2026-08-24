package com.port051.queuemate.matching.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 01 4.3 — 결정적 FCFS. 절차는 명세 그대로다.
 * <ol>
 *   <li>대기 요청에 {@code (신청 시각, 요청 ID)} 오름차순 전순서를 준다 — 호출자가 정렬해서 넘긴다.</li>
 *   <li>가장 앞선 요청을 기준으로 잡는다.</li>
 *   <li>조건이 맞고 포지션 배정이 가능한 후보를 순서대로 채택한다.</li>
 *   <li>정원을 채우면 성립, 못 채우면 다음 요청을 기준으로 넘어간다.</li>
 * </ol>
 *
 * <p>재회 우선(01 7.7)은 넣지 않았다. {@code user_relation}이 DB에 있어 실시간 매칭이 읽을 수 없다.
 * 넣는다면 후보 정렬 키를 {@code (재회 여부, 신청 시각, 요청 ID)}로 바꾸는 자리다.
 */
public final class PartyComposer {

    private final CandidateFilter filter;
    private final int searchBudget;

    public PartyComposer(CandidateFilter filter, int searchBudget) {
        this.filter = filter;
        this.searchBudget = searchBudget;
    }

    /**
     * @param pool         FCFS로 정렬된 대기 요청
     * @param comboAllowed 01 5.4 재제안 억제 — 거절로 깨진 조합은 10분간 다시 제안하지 않는다
     */
    public Optional<ComposedParty> compose(List<MatchRequestView> pool, Predicate<Set<Long>> comboAllowed) {
        int[] budget = {searchBudget};
        for (int base = 0; base < pool.size(); base++) {
            List<MatchRequestView> chosen = new ArrayList<>();
            chosen.add(pool.get(base));
            Optional<ComposedParty> found =
                    fill(pool, base + 1, chosen, pool.get(base).targetSize(), comboAllowed, budget);
            if (found.isPresent()) {
                return found;
            }
            if (budget[0] <= 0) {
                return Optional.empty();   // 탐색 예산 초과 — 다음 사이클에서 다시 시도한다
            }
        }
        return Optional.empty();
    }

    private Optional<ComposedParty> fill(List<MatchRequestView> pool,
                                         int from,
                                         List<MatchRequestView> chosen,
                                         int targetSize,
                                         Predicate<Set<Long>> comboAllowed,
                                         int[] budget) {
        if (chosen.size() == targetSize) {
            List<MatchRequestView> members = List.copyOf(chosen);
            ComposedParty candidate = PositionAssigner.assign(members)
                    .map(assignment -> new ComposedParty(members, assignment))
                    .orElse(null);
            if (candidate == null || !comboAllowed.test(candidate.combo())) {
                return Optional.empty();
            }
            return Optional.of(candidate);
        }
        for (int i = from; i < pool.size(); i++) {
            if (--budget[0] <= 0) {
                return Optional.empty();
            }
            MatchRequestView candidate = pool.get(i);
            if (candidate.targetSize() != targetSize || !compatibleWithAll(chosen, candidate)) {
                continue;
            }
            chosen.add(candidate);
            Optional<ComposedParty> result = PositionAssigner.feasible(chosen)
                    ? fill(pool, i + 1, chosen, targetSize, comboAllowed, budget)
                    : Optional.empty();
            chosen.removeLast();
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    private boolean compatibleWithAll(List<MatchRequestView> chosen, MatchRequestView candidate) {
        for (MatchRequestView member : chosen) {
            if (!filter.compatible(member, candidate)) {
                return false;
            }
        }
        return true;
    }
}

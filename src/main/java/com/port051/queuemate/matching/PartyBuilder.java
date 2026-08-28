package com.port051.queuemate.matching;

import com.port051.queuemate.contract.MatchRequestPayload;
import com.port051.queuemate.offer.ComboSuppressor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 결정적 후보 처리. 01 4.3.
 *
 * <p>명세가 절차를 다섯 단계로 못박아 두었고 이 클래스는 그것을 그대로 옮긴다.
 *
 * <ol>
 *   <li>모든 대기 요청에 {@code (신청 시각, 요청 ID)} 오름차순 전순서를 부여한다
 *       — {@link MatchRequestStore}의 Sorted Set이 이미 그 순서로 준다</li>
 *   <li>아직 배정되지 않은 요청 중 순서가 가장 앞선 것을 기준 요청으로 잡는다</li>
 *   <li>기준 요청과 조건이 맞는 후보를 훑으며 포지션 배정이 가능한 후보만 차례로 채택한다</li>
 *   <li>목표 인원을 채우면 성립시키고, 못 채우면 기준 요청을 그대로 둔 채 다음으로 넘어간다</li>
 *   <li>더 이상 성립하는 파티가 없으면 종료한다</li>
 * </ol>
 *
 * <p><b>결정성이 왜 중요한가.</b> 같은 요청 집합을 넣으면 항상 같은 파티 구성이 나와야
 * 배치를 두 번 돌려 결과를 비교할 수 있다. 이 클래스는 입력 목록의 순서 외에 아무것도 보지 않는다 —
 * 시각도, 난수도, 해시 순회 순서도 쓰지 않는다.
 *
 * <p><b>재회 목록 우선(01 4.3 3번)은 구현하지 않았다.</b> 스파이크 범위가 4·5장이라
 * 평가·재회(7.7)가 빠져 있고, 계약 JSON에도 그 필드가 없다(이슈 #48). 순서만 앞당기는 규칙이므로
 * 후보 목록 정렬 한 줄로 들어올 자리를 {@link #orderCandidates}에 비워 두었다.
 *
 * <p><b>이 구현은 탐욕적이다.</b> 기준 요청부터 앞에서부터 채우고 되돌아가지 않는다.
 * 전역 최적(가장 많은 파티를 성립시키는 조합)을 찾지 않으므로, 조건이 촘촘한 구간에서는
 * 성립 가능한 조합을 놓칠 수 있다. 명세가 요구하는 것은 최적이 아니라 결정성이고
 * (01 4.3 — "별도의 유사도·신뢰도 점수를 계산하지 않는다"), 성립률의 실제 손실은
 * 02 6.3의 성립률 곡선으로 재는 것이 판정일의 비교 축이다.
 */
@Component
public class PartyBuilder {

    private final CandidateFilter filter;
    private final ComboSuppressor suppressor;

    public PartyBuilder(CandidateFilter filter, ComboSuppressor suppressor) {
        this.filter = filter;
        this.suppressor = suppressor;
    }

    /**
     * 한 틱에 성립하는 파티를 전부 찾는다.
     *
     * @param ordered 전순서대로 정렬된 대기 요청. 순서가 곧 판정 기준이다
     * @return 성립한 파티들. 각 파티의 첫 원소가 기준 요청이다
     */
    public List<List<MatchRequestPayload>> build(List<MatchRequestPayload> ordered) {
        List<List<MatchRequestPayload>> parties = new ArrayList<>();
        Set<Long> taken = new HashSet<>();

        for (MatchRequestPayload base : ordered) {
            if (taken.contains(base.requestId())) continue;

            List<MatchRequestPayload> party = fill(base, ordered, taken);
            if (party == null) continue; // 정원을 못 채웠다. 기준 요청은 그대로 둔다

            party.forEach(member -> taken.add(member.requestId()));
            parties.add(party);
        }
        return parties;
    }

    /**
     * 기준 요청 하나로 파티를 채운다.
     *
     * <p>정원을 채웠는데 그 조합이 억제 중이면(01 5.4) 마지막으로 넣은 사람을 빼고
     * 다음 후보부터 다시 훑는다. 조합이 하나 다르면 다른 조합이므로 억제 대상이 아니기 때문이다.
     * 억제 때문에 통째로 포기하면, 한 번 거절당한 사람이 10분간 아무와도 못 묶인다.
     *
     * @return 정원을 채웠으면 파티, 못 채웠으면 null
     */
    private List<MatchRequestPayload> fill(
            MatchRequestPayload base, List<MatchRequestPayload> ordered, Set<Long> taken) {
        List<MatchRequestPayload> party = new ArrayList<>(base.targetSize());
        party.add(base);

        List<MatchRequestPayload> candidates = orderCandidates(base, ordered);
        int index = 0;
        // 억제된 조합을 만났을 때 마지막 한 명만 물리고 이어서 훑는다.
        // 되돌아간 지점을 기억해 같은 후보를 다시 시도하지 않는다.
        List<Integer> pickedAt = new ArrayList<>();

        while (party.size() < base.targetSize()) {
            boolean added = false;
            while (index < candidates.size()) {
                MatchRequestPayload candidate = candidates.get(index++);
                if (taken.contains(candidate.requestId())) continue;
                if (containsUser(party, candidate.userId())) continue;
                if (!filter.canJoin(party, candidate)) continue;

                party.add(candidate);
                pickedAt.add(index - 1);
                added = true;
                break;
            }
            if (!added) return null; // 후보가 떨어졌다

            if (party.size() == base.targetSize() && suppressed(party)) {
                party.remove(party.size() - 1); // 마지막 한 명만 물리고 계속 훑는다
                pickedAt.remove(pickedAt.size() - 1);
            }
        }
        return party;
    }

    /**
     * 후보 순서. 01 4.3 3번은 "재회 목록에 있는 사람이 먼저, 그다음이 신청 순서"라고 했다.
     * 재회가 범위 밖이므로 지금은 신청 순서 그대로다 — 즉 입력 순서를 건드리지 않는다.
     */
    private List<MatchRequestPayload> orderCandidates(
            MatchRequestPayload base, List<MatchRequestPayload> ordered) {
        List<MatchRequestPayload> candidates = new ArrayList<>(ordered.size());
        for (MatchRequestPayload other : ordered) {
            if (other.requestId() != base.requestId()) candidates.add(other);
        }
        return candidates;
    }

    private boolean suppressed(List<MatchRequestPayload> party) {
        return suppressor.isSuppressed(party.stream().map(MatchRequestPayload::requestId).toList());
    }

    private boolean containsUser(List<MatchRequestPayload> party, long userId) {
        return party.stream().anyMatch(member -> member.userId() == userId);
    }
}

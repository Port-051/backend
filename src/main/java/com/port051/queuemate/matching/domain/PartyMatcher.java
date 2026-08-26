package com.port051.queuemate.matching.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 대기 중인 요청에서 파티를 만든다. 01-functional-spec-mvp 4.3.
 *
 * <p>한 번 호출이 4.3의 처리 절차 한 바퀴에 해당한다. 실행 주기와 인스턴스 간 조정,
 * 대기 명단을 어디서 읽고 어떻게 지우는지는 여기서 다루지 않는다. 이 클래스에 들어오는 것은
 * <b>이미 읽어 온 요청 목록</b>뿐이고, 나가는 것은 성립한 파티 목록뿐이다.
 * 저장소를 모르므로 Redis 없이 통째로 테스트할 수 있다.
 *
 * <p><b>결정성(4.3)</b>이 이 클래스의 핵심 성질이다. 입력이 어떤 순서로 들어오든
 * {@code (신청 시각, 요청 ID)}로 정렬해서 시작하므로 같은 요청 집합에는 항상 같은 파티가 나온다.
 * 유사도 점수도, 대기시간에 따른 조건 완화도 없다.
 *
 * <p>후보 순서에서 <b>재회 목록 우선</b>(7.7)은 구현하지 않는다.
 * 재회 목록은 요청 바깥의 상태이고, 실시간 매칭 계약(05)이 판정 중 DB 조회를 금지하기 때문이다.
 * 같은 이유로 차단 쌍과 확정 파티 중복도 여기서 보지 않는다. 남는 것은 신청 순서 하나다.
 */
public final class PartyMatcher {

    /** 4.3 1단계의 전순서. 신청 시각이 같으면 요청 ID로 가른다. */
    private static final Comparator<MatchRequest> ORDER =
            Comparator.comparingLong(MatchRequest::requestedAt)
                    .thenComparingLong(MatchRequest::requestId);

    private PartyMatcher() {
    }

    /**
     * 대기 요청을 훑어 성립하는 파티를 전부 만든다.
     *
     * <p>한 요청은 최대 하나의 파티에만 들어간다(불변식 INV-3). 파티에 들어가지 못한 요청은
     * 그대로 대기 상태로 남으며, 다음 실행에서 다시 후보가 된다.
     *
     * @param waiting 대기 중인 요청. 순서는 상관없다
     * @return 성립한 파티. 만들어진 순서대로다
     */
    public static List<Party> match(List<MatchRequest> waiting) {
        List<MatchRequest> ordered = new ArrayList<>(waiting);
        ordered.sort(ORDER);

        Set<Long> assigned = new HashSet<>();
        List<Party> parties = new ArrayList<>();

        for (MatchRequest base : ordered) {
            if (assigned.contains(base.requestId())) {
                continue;
            }
            Optional<Party> party = buildFrom(base, ordered, assigned);
            if (party.isPresent()) {
                for (MatchRequest member : party.get().members()) {
                    assigned.add(member.requestId());
                }
                parties.add(party.get());
            }
            // 4.3 4단계 — 채우지 못했으면 기준 요청을 배정하지 않은 채로 둔다.
        }
        return List.copyOf(parties);
    }

    /**
     * 기준 요청에서 시작해 목표 인원을 채운다. 4.3 3·4단계.
     *
     * <p>후보를 하나 채택할 때마다 포지션을 <b>처음부터 다시</b> 배정한다. 자리를 잠그지 않으므로
     * 이미 앉아 있던 참가자가 물러나 새 후보에게 자리를 내줄 수 있다(4.4).
     *
     * <p>채택은 되돌리지 않는다. 4.3 4단계가 "채우지 못하면 기준 요청을 그대로 둔 채 다음 요청으로
     * 넘어간다"이므로, 앞서 채택한 후보를 물리고 다른 조합을 시도하지 않는다. 그 대가로 이론상
     * 성립 가능한 파티를 놓칠 수 있지만, 즉시 매칭은 짧은 주기로 다시 돌므로 다음 실행에서 잡힌다.
     */
    private static Optional<Party> buildFrom(MatchRequest base, List<MatchRequest> ordered, Set<Long> assigned) {
        int targetSize = base.targetSize();

        List<MatchRequest> members = new ArrayList<>(targetSize);
        members.add(base);
        PositionAssignment assignment = PositionAssigner.assign(members).orElseThrow();

        for (MatchRequest candidate : ordered) {
            if (members.size() == targetSize) {
                break;
            }
            if (candidate.requestId() == base.requestId() || assigned.contains(candidate.requestId())) {
                continue;
            }
            if (!compatibleWithAll(candidate, members)) {
                continue;
            }

            members.add(candidate);
            Optional<PositionAssignment> seated = PositionAssigner.assign(members);
            if (seated.isEmpty()) {
                // 조건은 맞지만 앉힐 자리가 없다. 채택하지 않고 다음 후보로 간다(4.3 3단계).
                members.removeLast();
                continue;
            }
            assignment = seated.get();
        }

        if (members.size() < targetSize) {
            return Optional.empty();
        }
        return Optional.of(new Party(members, assignment));
    }

    /**
     * 후보가 이미 채택된 참가자 <b>전원</b>과 조건이 맞는지. 4.3 3단계.
     *
     * <p>기준 요청 하나와만 대조하면 안 된다. 4.1의 조건 중 음성채팅 호환과 허용 티어 범위는
     * 한 다리 건너 성립하지 않기 때문이다. 기준이 {@code 가능}이면 {@code 필수}인 사람과
     * {@code 사용하지 않음}인 사람이 각각 기준과는 호환이지만 서로는 호환되지 않는데,
     * 아무도 그 둘을 비교하지 않으면 4.1을 위반한 파티가 성립한다.
     */
    private static boolean compatibleWithAll(MatchRequest candidate, List<MatchRequest> members) {
        for (MatchRequest member : members) {
            if (!CandidateRule.allSatisfied(candidate, member)) {
                return false;
            }
        }
        return true;
    }
}

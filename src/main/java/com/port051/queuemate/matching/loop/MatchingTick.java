package com.port051.queuemate.matching.loop;

import com.port051.queuemate.matching.domain.MatchRequest;
import com.port051.queuemate.matching.domain.Partition;
import com.port051.queuemate.matching.domain.Party;
import com.port051.queuemate.matching.domain.PartyMatcher;
import com.port051.queuemate.matching.store.ClaimStore;
import com.port051.queuemate.matching.store.RequestExpiry;
import com.port051.queuemate.matching.store.RequestStore;
import com.port051.queuemate.matching.store.WaitingList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 매칭 한 바퀴. 05-realtime-matching-contract 3장.
 *
 * <p>계약이 정한 절차는 넷이다.
 *
 * <ol>
 *   <li>대기 명단을 읽는다
 *   <li>메모를 보고 같이 앉힐 인원을 찾는다
 *   <li>찾은 전원에게 동시에 {@code claim}을 건다. 하나라도 이미 걸려 있으면 전부 취소하고 다음 사이클
 *   <li>확정을 알린다
 * </ol>
 *
 * <p>이 클래스는 <b>순서를 짜는 일만</b> 한다. 후보 판정과 포지션 배정은
 * {@link PartyMatcher}가, 저장은 각 저장소가 한다. 그래서 이 파일에는 매칭 규칙이 한 줄도 없다.
 *
 * <p><b>주기와 분리돼 있다.</b> {@link #runOnce()}는 스스로 반복하지 않으므로
 * 테스트가 원하는 시점에 한 바퀴만 돌릴 수 있다. 언제 얼마나 자주 부를지는
 * {@link MatchingScheduler}가 정한다.
 */
@Component
public class MatchingTick {

    private static final Logger log = LoggerFactory.getLogger(MatchingTick.class);

    private final WaitingList waitingList;
    private final RequestStore requestStore;
    private final ClaimStore claims;
    private final RequestExpiry expiry;

    public MatchingTick(WaitingList waitingList,
                        RequestStore requestStore,
                        ClaimStore claims,
                        RequestExpiry expiry) {
        this.waitingList = waitingList;
        this.requestStore = requestStore;
        this.claims = claims;
        this.expiry = expiry;
    }

    /**
     * 조합 전체를 한 바퀴 돈다.
     *
     * <p>만료를 <b>먼저</b> 정리한다. 순서가 반대면 이번 바퀴에서 만료될 요청이 파티에 들어가
     * "매칭 실패"와 "파티 확정"이 같은 사용자에게 함께 나간다.
     *
     * @return 이번 바퀴에 성립한 파티
     */
    public List<Party> runOnce() {
        long threshold = expiry.threshold();

        List<Party> confirmed = new ArrayList<>();
        for (Partition partition : Partition.all()) {
            expiry.sweep(partition, threshold);
            confirmed.addAll(runOnce(partition));
        }
        return List.copyOf(confirmed);
    }

    /**
     * 한 조합만 한 바퀴 돈다.
     *
     * <p>조합을 나눠 맡는 실행기를 두게 되면 이쪽이 진입점이 된다.
     */
    public List<Party> runOnce(Partition partition) {
        // 1. 대기 명단을 읽는다. 4.3의 전순서대로 나온다.
        List<Long> waiting = waitingList.requestIds(partition);
        if (waiting.size() < partition.targetSize()) {
            // 목표 인원도 안 되면 어떤 파티도 성립할 수 없다.
            return List.of();
        }

        // 2. 메모를 보고 같이 앉힐 인원을 찾는다.
        List<MatchRequest> requests = requestStore.findAll(waiting);
        List<Party> candidates = PartyMatcher.match(requests);
        if (candidates.isEmpty()) {
            return List.of();
        }

        // 3~4. 잡을 수 있는 파티만 확정한다.
        List<Party> confirmed = new ArrayList<>(candidates.size());
        for (Party party : candidates) {
            if (confirm(partition, party)) {
                confirmed.add(party);
            }
        }
        return confirmed;
    }

    /**
     * 파티 하나를 확정한다.
     *
     * <p>다른 인스턴스가 같은 요청으로 파티를 짜고 있으면 배정 중 표시에서 진다.
     * 진 쪽은 아무것도 남기지 않고 물러난다. 이번 바퀴를 통째로 포기하지는 않는다.
     * 그 파티만 못 만들 뿐 다른 파티는 여전히 성립할 수 있기 때문이다.
     */
    private boolean confirm(Partition partition, Party party) {
        List<Long> requestIds = party.members().stream().map(MatchRequest::requestId).toList();

        Optional<String> owner = claims.claimAll(requestIds);
        if (owner.isEmpty()) {
            log.debug("배정 중 표시를 얻지 못해 물러난다: {}", requestIds);
            return false;
        }

        try {
            // 명단 먼저, 메모 나중. 순서가 반대면 조건을 읽을 수 없는 요청이 잠깐 명단에 남는다.
            waitingList.removeAll(partition, requestIds);
            requestStore.deleteAll(requestIds);
            log.info("파티 성립: {} {}", partition, requestIds);
            return true;
        } finally {
            // 명단에서 빠졌으니 표시를 들고 있을 이유가 없다. 떼지 않아도 TTL이 풀지만
            // 그동안 같은 요청 ID가 묶여 있게 된다.
            claims.releaseAll(requestIds, owner.get());
        }
    }
}

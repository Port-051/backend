package com.port051.queuemate.matching;

import com.port051.queuemate.config.MatchingProperties;
import com.port051.queuemate.contract.MatchRequestPayload;
import com.port051.queuemate.contract.Queue;
import com.port051.queuemate.contract.RequestState;
import com.port051.queuemate.offer.OfferService;
import com.port051.queuemate.sse.EventFanout;
import com.port051.queuemate.sse.EventType;
import com.port051.queuemate.sse.MatchEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 즉시 매칭 틱 루프. 01 4장 · 02 1.4.
 *
 * <p>01 4장은 "서버가 짧은 주기로 대기 중인 요청을 훑어 실행한다. 신청 즉시 한 번만 시도하는 것이
 * 아니라, 요청이 대기하는 동안 반복해서 후보를 다시 찾는다"고 했다. 그 주기가 이 클래스다.
 *
 * <p><b>두 인스턴스가 같은 명단을 자유롭게 훑는다.</b> 02 1.4는 이것을 그대로 두고
 * 경합을 INV-3이 흡수하게 하는 것과, 큐 단위로 실행기를 나누는 파티션 단일 라이터를
 * <b>비교 대상</b>으로 남겼다. 이 구현은 <b>전자</b>다 — 파티션을 나누지 않고 둘 다 전부 훑는다.
 *
 * <p>고른 이유는 성립률이다. 큐·정원 조합이 여덟(솔로듀오 1 + 자유 3 + 일반 4)인데 인스턴스가
 * 둘이면, 파티션을 나누는 순간 한 인스턴스가 죽었을 때 그쪽 파티션의 매칭이 통째로 멈춘다.
 * 대신 두 인스턴스가 같은 후보를 집는 낭비가 생기고, 그 낭비의 크기가 판정일에 잴 값이다.
 * 선점({@link ClaimManager})이 그 낭비를 얼마나 줄이는지가 이 축의 결론이 된다.
 *
 * <p><b>낭비의 실제 모양.</b> 두 인스턴스가 같은 후보로 파티를 짜면 선점에서 하나가 진다.
 * 진 쪽은 그 사이클을 버리고 다음 틱에 다시 훑는다 — 요청이 손실되지는 않고 지연만 는다.
 * 선점이 만료된 뒤 확정까지 간 경우에도 {@code confirm_party.lua}가 INV-3으로 막는다.
 */
@Component
public class MatchingLoop {

    private static final Logger log = LoggerFactory.getLogger(MatchingLoop.class);

    private final MatchRequestStore requests;
    private final PartyBuilder builder;
    private final ClaimManager claims;
    private final OfferService offerService;
    private final EventFanout fanout;
    private final MatchingProperties properties;

    private final AtomicLong ticks = new AtomicLong();
    private final AtomicLong proposed = new AtomicLong();
    private final AtomicLong claimLost = new AtomicLong();

    public MatchingLoop(
            MatchRequestStore requests,
            PartyBuilder builder,
            ClaimManager claims,
            OfferService offerService,
            EventFanout fanout,
            MatchingProperties properties) {
        this.requests = requests;
        this.builder = builder;
        this.claims = claims;
        this.offerService = offerService;
        this.fanout = fanout;
        this.properties = properties;
    }

    @Scheduled(fixedRateString = "${queuemate.matching.tick:200ms}")
    public void tick() {
        ticks.incrementAndGet();
        for (Queue queue : Queue.values()) {
            for (int targetSize : queue.allowedSizes()) {
                try {
                    runPartition(queue, targetSize);
                } catch (Exception e) {
                    log.warn("틱 실패 queue={} size={}", queue, targetSize, e);
                }
            }
        }
    }

    /** 큐·정원 하나를 훑는다. 대기 명단이 이미 이 단위로 갈려 있다. */
    void runPartition(Queue queue, int targetSize) {
        List<Long> waitingIds = requests.waitingIds(queue, targetSize, properties.batchSize());
        if (waitingIds.size() < targetSize) return; // 정원도 안 되면 볼 것이 없다

        List<MatchRequestPayload> ordered = requests.loadAll(waitingIds);
        ordered = dropExpired(ordered);
        ordered = dropBusy(ordered);
        if (ordered.size() < targetSize) return;

        for (List<MatchRequestPayload> party : builder.build(ordered)) {
            propose(party);
        }
    }

    /**
     * 파티 하나를 선점하고 제안한다.
     *
     * <p>선점 → 포지션 배정 → 제안 → 선점 해제 순이다. 제안이 만들어지는 순간 참가자가
     * 명단에서 빠지므로(01 5.1 경로) 그 뒤로는 선점이 필요 없다. 오래 붙들면 이 인스턴스가
     * 죽었을 때 TTL만큼 그 요청들이 얼어붙는다.
     */
    private void propose(List<MatchRequestPayload> party) {
        List<Long> requestIds = party.stream().map(MatchRequestPayload::requestId).toList();
        String token = ClaimManager.newToken();

        if (!claims.claimAll(requestIds, token)) {
            // 다른 인스턴스가 이 후보 중 하나를 먼저 잡았다. 이번 사이클은 버린다.
            claimLost.incrementAndGet();
            return;
        }

        try {
            Optional<PositionAssignment> assignment = PositionAssigner.assign(party);
            if (assignment.isEmpty()) {
                // 여기 오면 안 된다 — CandidateFilter가 얹을 때마다 feasible을 확인했다.
                // 그래도 방어한다. 배정이 불가능한 조합은 파티를 성립시키지 않는다(01 4.4).
                log.warn("배정 불가능한 조합이 정원까지 채워졌다 requestIds={}", requestIds);
                return;
            }
            offerService.propose(party, assignment.get().byRequestId(), System.currentTimeMillis());
            proposed.incrementAndGet();
        } finally {
            claims.release(requestIds, token);
        }
    }

    /**
     * 최대 대기시간이 지난 요청을 걷어낸다. 01 10.2.
     *
     * <p>실패 사유 안내(01 4.5)는 P1이고 스파이크 범위 밖이라 사유 없이 종료만 한다.
     */
    private List<MatchRequestPayload> dropExpired(List<MatchRequestPayload> ordered) {
        long deadline = System.currentTimeMillis() - properties.maxWait().toMillis();
        List<MatchRequestPayload> alive = new ArrayList<>(ordered.size());
        for (MatchRequestPayload payload : ordered) {
            if (payload.requestedAt() > deadline) {
                alive.add(payload);
                continue;
            }
            requests.fail(payload);
            fanout.publish(
                    MatchEvent.of(
                            EventType.MATCH_FAILED, payload.userId(), payload.requestId(), null));
        }
        return alive;
    }

    /**
     * 이미 시간이 겹치는 확정 파티에 속한 사용자를 후보에서 뺀다. 01 4.1.
     *
     * <p>즉시 매칭의 시작 시각은 확정 시각이므로(01 3.1) "지금 점유 중인가"를 묻는 것과 같다.
     * 실제로 막는 자리는 {@code confirm_party.lua}이고 여기는 낭비를 줄이는 사전 필터다.
     */
    private List<MatchRequestPayload> dropBusy(List<MatchRequestPayload> ordered) {
        if (ordered.isEmpty()) return ordered;
        long now = System.currentTimeMillis();
        java.util.Set<Long> userIds = new java.util.LinkedHashSet<>();
        ordered.forEach(p -> userIds.add(p.userId()));

        java.util.Set<Long> busy = requests.busyUserIds(userIds, now);
        if (busy.isEmpty()) return ordered;

        List<MatchRequestPayload> free = new ArrayList<>(ordered.size());
        for (MatchRequestPayload payload : ordered) {
            if (!busy.contains(payload.userId())) free.add(payload);
        }
        return free;
    }

    /** 판정일 비교용 카운터. Actuator로 노출하지 않고 로그·테스트에서 읽는다. */
    public record Stats(long ticks, long proposed, long claimLost) {}

    public Stats stats() {
        return new Stats(ticks.get(), proposed.get(), claimLost.get());
    }
}

package com.port051.queuemate.matching.matcher;

import com.port051.queuemate.matching.config.MatchingProperties;
import com.port051.queuemate.matching.domain.ComposedParty;
import com.port051.queuemate.matching.domain.MatchRequestView;
import com.port051.queuemate.matching.domain.PartyComposer;
import com.port051.queuemate.matching.offer.OfferService;
import com.port051.queuemate.matching.offer.OfferStore;
import com.port051.queuemate.matching.redis.ClaimService;
import com.port051.queuemate.matching.redis.EventPublisher;
import com.port051.queuemate.matching.redis.Keys;
import com.port051.queuemate.matching.redis.MatchingEvent;
import com.port051.queuemate.matching.redis.RedisClock;
import com.port051.queuemate.matching.redis.RequestRepository;
import com.port051.queuemate.matching.redis.SuppressionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 02 1.4 — 즉시 매칭 실행기. 각 인스턴스가 주기적으로 대기 명단을 훑는다.
 *
 * <p>두 인스턴스가 같은 대기 풀을 자유롭게 훑으므로 그 자체가 경합이다. 이 스파이크에서
 * 경합을 흡수하는 것은 Lua 원자 선점 하나뿐이다 — DB 유니크 제약이 없다.
 * README 2단계에서 비관적 락·낙관적 락·파티션 단일 라이터와 비교할 때 이 구현이 한 축이 된다.
 *
 * <p>사이클 하나가 하는 일은 넷이다.
 * <ol>
 *   <li>{@code ZRANGE} — 대기 명단을 앞에서부터 읽는다</li>
 *   <li>{@code MGET} — 메모를 읽고 조건을 비교한다 (DB를 보지 않는다)</li>
 *   <li>{@code EVAL} — 참가자 전원에게 포스트잇을 동시에 붙인다</li>
 *   <li>{@code PUBLISH} — 제안이 생겼다고 알린다</li>
 * </ol>
 */
@Component
public class MatchingLoop {

    private static final Logger log = LoggerFactory.getLogger(MatchingLoop.class);

    private final RequestRepository requests;
    private final ClaimService claims;
    private final SuppressionRepository suppression;
    private final OfferService offerService;
    private final OfferStore offers;
    private final EventPublisher events;
    private final RedisClock clock;
    private final PartyComposer composer;
    private final MatchingProperties properties;
    private final Counter offersCreated;
    private final Counter claimConflicts;
    private final Timer cycleTimer;

    public MatchingLoop(RequestRepository requests,
                        ClaimService claims,
                        SuppressionRepository suppression,
                        OfferService offerService,
                        OfferStore offers,
                        EventPublisher events,
                        RedisClock clock,
                        PartyComposer composer,
                        MatchingProperties properties,
                        MeterRegistry meters) {
        this.requests = requests;
        this.claims = claims;
        this.suppression = suppression;
        this.offerService = offerService;
        this.offers = offers;
        this.events = events;
        this.clock = clock;
        this.composer = composer;
        this.properties = properties;
        this.offersCreated = meters.counter("matching.offers.created");
        this.claimConflicts = meters.counter("matching.claim.conflicts");
        this.cycleTimer = meters.timer("matching.cycle");
    }

    @Scheduled(fixedDelayString = "${matching.cycle-interval}",
            initialDelayString = "${matching.initial-delay}")
    public void tick() {
        cycleTimer.record(() -> {
            for (String partition : requests.partitions()) {
                try {
                    runPartition(partition);
                } catch (RuntimeException e) {
                    log.warn("파티션 {} 사이클 실패", partition, e);
                }
            }
        });
    }

    /** 01 10.2 — 수락 제한시간이 지난 제안을 자동 거절 처리한다. */
    @Scheduled(fixedDelayString = "${matching.cycle-interval}",
            initialDelayString = "${matching.initial-delay}")
    public void sweepOffers() {
        offerService.sweepExpired();
    }

    void runPartition(String partition) {
        long now = clock.nowMillis();
        List<MatchRequestView> pool = new ArrayList<>(requests.scan(partition, properties.scanWindow()));
        pool.removeIf(request -> expireIfDue(request, now));

        Set<Long> claimed = requests.claimedAmong(pool.stream().map(MatchRequestView::requestId).toList());
        pool.removeIf(request -> claimed.contains(request.requestId()));

        int created = 0;
        while (created < properties.maxOffersPerCycle()) {
            Optional<ComposedParty> composed = composer.compose(pool, suppression::allowed);
            if (composed.isEmpty()) {
                return;
            }
            ComposedParty party = composed.get();
            long offerId = offers.nextOfferId();
            if (claims.claim(party, offerId, properties.acceptWindow())) {
                offerService.create(party, offerId, clock.nowMillis());
                offersCreated.increment();
                created++;
                pool.removeAll(party.members());
            } else {
                // 02 1.4 — 진 쪽의 파티 구성은 폐기한다. 선점된 멤버만 풀에서 걷어내고,
                // 남은 사람은 이번 사이클에서 다른 조합으로 다시 쓴다.
                claimConflicts.increment();
                Set<Long> nowClaimed = requests.claimedAmong(party.combo().stream().toList());
                if (nowClaimed.isEmpty()) {
                    pool.removeAll(party.members());   // 명단에서 빠진 경우(취소·만료가 이겼다)
                } else {
                    pool.removeIf(member -> nowClaimed.contains(member.requestId()));
                }
            }
        }
    }

    /** 01 10.2 — 최대 대기시간 만료. 선점 중인 요청은 제안이 끝날 때까지 건드리지 않는다. */
    private boolean expireIfDue(MatchRequestView request, long now) {
        if (!request.expiredAt(now)) {
            return false;
        }
        if (requests.claimedBy(request.requestId()).isPresent()) {
            return true;   // 제안 중이므로 풀에서만 빼고 만료는 미룬다
        }
        requests.expire(request);
        events.publish(MatchingEvent.MATCH_FAILED, List.of(request.userId()), Map.of(
                "requestId", request.requestId(),
                "reason", "MAX_WAIT_EXCEEDED",
                "queue", request.queue().name(),
                "partition", Keys.partition(request.queue(), request.targetSize())));
        return true;
    }
}

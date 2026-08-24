package com.port051.queuemate.matching.offer;

import com.port051.queuemate.matching.config.MatchingProperties;
import com.port051.queuemate.matching.domain.ComposedParty;
import com.port051.queuemate.matching.domain.MatchRequestView;
import com.port051.queuemate.matching.redis.ClaimService;
import com.port051.queuemate.matching.redis.EventPublisher;
import com.port051.queuemate.matching.redis.Keys;
import com.port051.queuemate.matching.redis.MatchingEvent;
import com.port051.queuemate.matching.redis.RedisClock;
import com.port051.queuemate.matching.redis.RequestRepository;
import com.port051.queuemate.matching.redis.SuppressionRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/** 01 5.2 · 5.3 · 5.4 · 10.2 — 제안의 수명 전체. */
@Service
public class OfferService {

    private static final Logger log = LoggerFactory.getLogger(OfferService.class);

    private static final String ACCEPTED = "ACCEPTED";
    private static final String DECLINED = "DECLINED";
    private static final String CONFIRMED = "CONFIRMED";
    private static final String TIMEOUT = "TIMEOUT";

    private final OfferStore offers;
    private final RequestRepository requests;
    private final ClaimService claims;
    private final SuppressionRepository suppression;
    private final EventPublisher events;
    private final RedisClock clock;
    private final StringRedisTemplate redis;
    private final RedisScript<Long> confirmScript;
    private final MatchingProperties properties;

    public OfferService(OfferStore offers,
                        RequestRepository requests,
                        ClaimService claims,
                        SuppressionRepository suppression,
                        EventPublisher events,
                        RedisClock clock,
                        StringRedisTemplate redis,
                        RedisScript<Long> confirmScript,
                        MatchingProperties properties) {
        this.offers = offers;
        this.requests = requests;
        this.claims = claims;
        this.suppression = suppression;
        this.events = events;
        this.clock = clock;
        this.redis = redis;
        this.confirmScript = confirmScript;
        this.properties = properties;
    }

    /** 선점에 성공한 조합을 제안으로 만든다. 참가자 전원에게 같은 이벤트가 간다. */
    public Offer create(ComposedParty party, long offerId, long createdAt) {
        List<Offer.Participant> participants = party.members().stream()
                .map(member -> new Offer.Participant(
                        member.requestId(),
                        member.userId(),
                        party.positions().byRequestId().get(member.requestId()),
                        member.playMinutes()))
                .toList();
        MatchRequestView first = party.members().getFirst();
        Offer offer = new Offer(offerId, first.queue(), first.targetSize(),
                createdAt, createdAt + properties.acceptWindow().toMillis(), participants);

        offers.save(offer, properties.acceptWindow().plusMinutes(5));

        events.publish(MatchingEvent.OFFER_CREATED, offer.userIds(), Map.of(
                "offerId", offer.offerId(),
                "queue", offer.queue().name(),
                "targetSize", offer.targetSize(),
                "createdAt", offer.createdAt(),
                "expiresAt", offer.expiresAt(),
                "voiceParty", party.voiceParty(),
                "positions", offer.positionsByUserId()));
        return offer;
    }

    public Offer require(long offerId) {
        return offers.find(offerId)
                .orElseThrow(() -> new NoSuchElementException("제안을 찾을 수 없다: " + offerId));
    }

    public record RespondResult(String state, int accepted, int targetSize, Long partyId) {
    }

    /**
     * 01 5.2 — 수락·거절. 같은 요청이 두 번 와도 한 번만 반영한다.
     * 01 5.3 — 전원이 수락했을 때만 확정하고, 한 명이라도 거절·시간초과면 제안을 종료한다.
     */
    public RespondResult respond(long offerId, long requestId, boolean accept, boolean keepSearching) {
        Offer offer = require(offerId);
        Offer.Participant participant = offer.participantOf(requestId)
                .orElseThrow(() -> new IllegalArgumentException("이 제안의 참가자가 아니다: " + requestId));

        Optional<String> settled = offers.outcome(offerId);
        if (settled.isPresent()) {
            return new RespondResult(settled.get(), acceptedCount(offerId), offer.targetSize(), null);
        }
        // 02 3.3 — 응답 시점의 지연 판정. 만료된 제안에 응답이 오면 그 자리에서 처리한다.
        if (clock.nowMillis() >= offer.expiresAt()) {
            expire(offer);
            return new RespondResult(TIMEOUT, acceptedCount(offerId), offer.targetSize(), null);
        }

        offers.recordResponse(offerId, requestId, accept ? ACCEPTED : DECLINED);

        if (!accept) {
            return decline(offer, participant, keepSearching);
        }
        events.publish(MatchingEvent.OFFER_RESPONSE_UPDATED, offer.userIds(), Map.of(
                "offerId", offerId,
                "accepted", acceptedCount(offerId),
                "targetSize", offer.targetSize()));

        if (acceptedCount(offerId) == offer.targetSize()) {
            return confirm(offer);
        }
        return new RespondResult("PENDING", acceptedCount(offerId), offer.targetSize(), null);
    }

    private RespondResult decline(Offer offer, Offer.Participant decliner, boolean keepSearching) {
        if (!offers.markOutcome(offer.offerId(), DECLINED, properties.acceptWindow().plusMinutes(5))) {
            return new RespondResult(offers.outcome(offer.offerId()).orElse(DECLINED),
                    acceptedCount(offer.offerId()), offer.targetSize(), null);
        }
        // 01 5.4 — 명시적 거절로 깨진 조합만 억제한다.
        suppression.suppress(offer.requestIds(), properties.comboSuppression());
        releaseAll(offer);

        // 01 5.3 — 수락했던 이용자는 기존 대기 순서를 유지한 채 다시 대기한다.
        // ZSET의 score를 건드리지 않았으므로 순서는 그대로다.
        if (!keepSearching) {
            requests.find(decliner.requestId()).ifPresent(requests::expire);
        }
        events.publish(MatchingEvent.OFFER_DECLINED, offer.userIds(), Map.of(
                "offerId", offer.offerId(),
                "reason", "DECLINED"));   // 01 5.2 — 누가 거절했는지는 공개하지 않는다
        offers.dropFromIndex(offer.offerId());
        return new RespondResult(DECLINED, acceptedCount(offer.offerId()), offer.targetSize(), null);
    }

    /**
     * 파티 확정. 실제 아키텍처에서는 Core API가 RDB에 쓰고 이 이벤트를 발행한다(11.3).
     * 스파이크에서는 대기 명단 정리까지만 한다.
     */
    private RespondResult confirm(Offer offer) {
        if (!offers.markOutcome(offer.offerId(), CONFIRMED, properties.acceptWindow().plusMinutes(30))) {
            return new RespondResult(offers.outcome(offer.offerId()).orElse(CONFIRMED),
                    offer.targetSize(), offer.targetSize(), null);
        }
        long partyId = offers.nextPartyId();
        long startAt = clock.nowMillis();

        List<String> keys = new ArrayList<>();
        keys.add(Keys.partition(offer.queue(), offer.targetSize()));
        offer.participants().forEach(p -> keys.add(Keys.request(p.requestId())));
        offer.participants().forEach(p -> keys.add(Keys.claim(p.requestId())));
        offer.participants().forEach(p -> keys.add(Keys.userClaim(p.userId())));
        offer.participants().forEach(p -> keys.add(Keys.userRequests(p.userId())));

        List<String> args = new ArrayList<>();
        args.add(String.valueOf(offer.participants().size()));
        offer.participants().forEach(p -> args.add(String.valueOf(p.requestId())));
        redis.execute(confirmScript, keys, args.toArray());
        offers.dropFromIndex(offer.offerId());

        Map<String, Object> payload = new HashMap<>();
        payload.put("offerId", offer.offerId());
        payload.put("partyId", partyId);
        payload.put("queue", offer.queue().name());
        payload.put("targetSize", offer.targetSize());
        payload.put("startAt", startAt);
        payload.put("endAt", startAt + offer.partyMinutes() * 60_000L);
        payload.put("positions", offer.positionsByUserId());
        events.publish(MatchingEvent.PARTY_CONFIRMED, offer.userIds(), payload);

        log.info("파티 확정 partyId={} offerId={} members={}", partyId, offer.offerId(), offer.requestIds());
        return new RespondResult(CONFIRMED, offer.targetSize(), offer.targetSize(), partyId);
    }

    /** 01 10.2 — 수락 제한시간이 지난 제안은 자동 거절 처리한다. 억제는 걸지 않는다(01 5.4). */
    public void expire(Offer offer) {
        if (!offers.markOutcome(offer.offerId(), TIMEOUT, properties.acceptWindow().plusMinutes(5))) {
            return;
        }
        releaseAll(offer);
        offers.dropFromIndex(offer.offerId());
        events.publish(MatchingEvent.OFFER_EXPIRED, offer.userIds(), Map.of(
                "offerId", offer.offerId(),
                "reason", TIMEOUT));
    }

    public int sweepExpired() {
        long now = clock.nowMillis();
        int swept = 0;
        for (Long offerId : offers.expiredOfferIds(now)) {
            Optional<Offer> offer = offers.find(offerId);
            if (offer.isEmpty()) {
                offers.dropFromIndex(offerId);
                continue;
            }
            expire(offer.get());
            swept++;
        }
        return swept;
    }

    private void releaseAll(Offer offer) {
        claims.release(offer.requestIds(), offer.userIds(), offer.offerId());
    }

    private int acceptedCount(long offerId) {
        return (int) offers.responses(offerId).values().stream()
                .filter(ACCEPTED::equals)
                .count();
    }

    public Duration acceptWindow() {
        return properties.acceptWindow();
    }
}

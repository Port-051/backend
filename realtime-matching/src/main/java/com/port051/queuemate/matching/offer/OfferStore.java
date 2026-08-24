package com.port051.queuemate.matching.offer;

import com.port051.queuemate.matching.redis.Keys;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class OfferStore {

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public OfferStore(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public long nextOfferId() {
        Long id = redis.opsForValue().increment(Keys.SEQ_OFFER);
        return id == null ? 0L : id;
    }

    public long nextPartyId() {
        Long id = redis.opsForValue().increment(Keys.SEQ_PARTY);
        return id == null ? 0L : id;
    }

    public void save(Offer offer, Duration ttl) {
        redis.opsForValue().set(Keys.offer(offer.offerId()), json.writeValueAsString(offer), ttl);
        redis.expire(Keys.offerResponses(offer.offerId()), ttl);
        redis.opsForZSet().add(Keys.OFFER_INDEX, String.valueOf(offer.offerId()), offer.expiresAt());
    }

    public Optional<Offer> find(long offerId) {
        String payload = redis.opsForValue().get(Keys.offer(offerId));
        return Optional.ofNullable(payload).map(p -> json.readValue(p, Offer.class));
    }

    /** 01 5.2 — 중복 클릭이나 재전송에도 같은 요청을 한 번만 처리한다. */
    public boolean recordResponse(long offerId, long requestId, String response) {
        Boolean first = redis.opsForHash()
                .putIfAbsent(Keys.offerResponses(offerId), String.valueOf(requestId), response);
        return Boolean.TRUE.equals(first);
    }

    public Map<Object, Object> responses(long offerId) {
        return redis.opsForHash().entries(Keys.offerResponses(offerId));
    }

    /** 제안 종료를 한 번만 일어나게 하는 가드. true를 받은 호출자만 종료 처리를 진행한다. */
    public boolean markOutcome(long offerId, String outcome, Duration ttl) {
        return Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent(Keys.offerOutcome(offerId), outcome, ttl));
    }

    public Optional<String> outcome(long offerId) {
        return Optional.ofNullable(redis.opsForValue().get(Keys.offerOutcome(offerId)));
    }

    /** 만료 시각이 지난 제안. 02 1.5의 공용 시계 값을 넘긴다. */
    public List<Long> expiredOfferIds(long nowMillis) {
        Set<String> ids = redis.opsForZSet().rangeByScore(Keys.OFFER_INDEX, 0, nowMillis);
        return ids == null ? List.of() : ids.stream().map(Long::parseLong).toList();
    }

    public void dropFromIndex(long offerId) {
        redis.opsForZSet().remove(Keys.OFFER_INDEX, String.valueOf(offerId));
    }
}

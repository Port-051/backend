package com.port051.queuemate.offer;

import com.port051.queuemate.config.MatchingProperties;
import com.port051.queuemate.contract.MatchRequestPayload;
import com.port051.queuemate.contract.Position;
import com.port051.queuemate.contract.Queue;
import com.port051.queuemate.contract.RedisKeys;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 제안 저장. {@code offer:{id}} 해시와 {@code offer:{id}:responses} 해시를 다룬다.
 *
 * <p>계약 밖 자율 영역이다(05). 해시 둘로 나눈 이유는 응답이 참가자 수만큼 갱신되는데
 * 제안 본문은 만들고 나면 status 말고 바뀌지 않기 때문이다. 한 해시에 섞으면
 * 응답을 세려고 본문 필드까지 매번 읽는다.
 */
@Component
public class OfferStore {

    private final StringRedisTemplate redis;
    private final MatchingProperties properties;
    private final RedisScript<String> respondOfferScript;
    private final RedisScript<Long> expireOfferScript;

    public OfferStore(
            StringRedisTemplate redis,
            MatchingProperties properties,
            RedisScript<String> respondOfferScript,
            RedisScript<Long> expireOfferScript) {
        this.redis = redis;
        this.properties = properties;
        this.respondOfferScript = respondOfferScript;
        this.expireOfferScript = expireOfferScript;
    }

    public long nextOfferId() {
        Long id = redis.opsForValue().increment(RedisKeys.SEQ_OFFER);
        return id == null ? 1L : id;
    }

    /** 제안을 만든다. 참가자 전원의 응답을 {@code PENDING}으로 깔아 둔다. */
    public Offer create(
            long offerId,
            List<MatchRequestPayload> party,
            Map<Long, Position> positions,
            long now) {

        MatchRequestPayload base = party.get(0);
        long expiresAt = now + properties.offerTtl().toMillis();
        String comboHash =
                ComboSuppressor.hash(party.stream().map(MatchRequestPayload::requestId).toList());

        Map<String, String> body = new LinkedHashMap<>();
        body.put("offerId", String.valueOf(offerId));
        body.put("queue", base.queue().name());
        body.put("targetSize", String.valueOf(base.targetSize()));
        body.put("createdAt", String.valueOf(now));
        body.put("expiresAt", String.valueOf(expiresAt));
        body.put("status", OfferStatus.PENDING.name());
        body.put("comboHash", comboHash);

        Map<String, String> responses = new LinkedHashMap<>();
        Map<Long, Position> members = new LinkedHashMap<>();
        Map<Long, Long> userIds = new LinkedHashMap<>();
        for (MatchRequestPayload member : party) {
            body.put("pos:" + member.requestId(), positions.get(member.requestId()).name());
            body.put("uid:" + member.requestId(), String.valueOf(member.userId()));
            responses.put(String.valueOf(member.requestId()), "PENDING");
            members.put(member.requestId(), positions.get(member.requestId()));
            userIds.put(member.requestId(), member.userId());
        }

        redis.opsForHash().putAll(RedisKeys.offer(offerId), body);
        redis.opsForHash().putAll(RedisKeys.offerResponses(offerId), responses);
        // 제안은 오래 살 이유가 없다. 만료 뒤 잠깐 남겨 늦은 응답에 만료를 알려준 다음 사라진다.
        redis.expire(RedisKeys.offer(offerId), properties.offerTtl().multipliedBy(6));
        redis.expire(RedisKeys.offerResponses(offerId), properties.offerTtl().multipliedBy(6));
        // 만료 스위퍼가 읽을 자리. 만료 시각 순으로 정렬돼 있어 "지금 지난 것"만 잘라 읽는다.
        redis.opsForZSet().add(RedisKeys.PENDING_OFFERS, String.valueOf(offerId), expiresAt);

        return new Offer(
                offerId,
                base.queue(),
                base.targetSize(),
                now,
                expiresAt,
                OfferStatus.PENDING,
                comboHash,
                members,
                userIds);
    }

    public Optional<Offer> load(long offerId) {
        Map<Object, Object> raw = redis.opsForHash().entries(RedisKeys.offer(offerId));
        if (raw.isEmpty()) return Optional.empty();

        Map<Long, Position> members = new LinkedHashMap<>();
        Map<Long, Long> userIds = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : raw.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (key.startsWith("pos:")) {
                members.put(Long.parseLong(key.substring(4)), Position.valueOf(String.valueOf(entry.getValue())));
            } else if (key.startsWith("uid:")) {
                userIds.put(Long.parseLong(key.substring(4)), Long.parseLong(String.valueOf(entry.getValue())));
            }
        }

        return Optional.of(
                new Offer(
                        offerId,
                        Queue.valueOf(str(raw, "queue")),
                        Integer.parseInt(str(raw, "targetSize")),
                        Long.parseLong(str(raw, "createdAt")),
                        Long.parseLong(str(raw, "expiresAt")),
                        OfferStatus.valueOf(str(raw, "status")),
                        str(raw, "comboHash"),
                        members,
                        userIds));
    }

    /**
     * 수락·거절을 반영한다. 멱등성과 전원 수락 판정이 Lua 안에서 한 번에 일어난다.
     *
     * @return {@code respond_offer.lua}의 반환 문자열 그대로
     */
    public String respond(long offerId, long requestId, String verdict, long now) {
        return redis.execute(
                respondOfferScript,
                List.of(RedisKeys.offer(offerId), RedisKeys.offerResponses(offerId)),
                String.valueOf(requestId),
                verdict,
                String.valueOf(now));
    }

    /** 제한시간 초과로 제안을 닫는다. 이 호출이 실제로 닫았을 때만 true. */
    public boolean expire(long offerId, long now) {
        Long result =
                redis.execute(
                        expireOfferScript, List.of(RedisKeys.offer(offerId)), String.valueOf(now));
        boolean expired = result != null && result == 1L;
        if (expired) unwatch(offerId);
        return expired;
    }

    public void markStatus(long offerId, OfferStatus status) {
        redis.opsForHash().put(RedisKeys.offer(offerId), "status", status.name());
        if (status != OfferStatus.PENDING) {
            redis.opsForZSet().remove(RedisKeys.PENDING_OFFERS, String.valueOf(offerId));
        }
    }

    /** 만료 시각이 지난 제안 id. 스위퍼가 읽는다. */
    public List<Long> expiredOfferIds(long now, int limit) {
        java.util.Set<String> ids =
                redis.opsForZSet()
                        .rangeByScore(RedisKeys.PENDING_OFFERS, 0, now, 0, limit);
        if (ids == null || ids.isEmpty()) return List.of();
        return ids.stream().map(Long::parseLong).toList();
    }

    /** 끝난 제안을 대기 목록에서 뺀다. */
    public void unwatch(long offerId) {
        redis.opsForZSet().remove(RedisKeys.PENDING_OFFERS, String.valueOf(offerId));
    }

    /** 참가자별 응답. 현황을 익명으로 보여줄 때 쓴다(01 5.2). */
    public Map<Long, String> responses(long offerId) {
        Map<Object, Object> raw = redis.opsForHash().entries(RedisKeys.offerResponses(offerId));
        Map<Long, String> result = new LinkedHashMap<>();
        raw.forEach((k, v) -> result.put(Long.parseLong(String.valueOf(k)), String.valueOf(v)));
        return result;
    }

    /** 거절한 요청 목록. 5.3의 "정상 이용자만 재대기"를 가르는 기준이다. */
    public List<Long> declinedRequestIds(long offerId) {
        List<Long> declined = new ArrayList<>();
        responses(offerId).forEach((requestId, verdict) -> {
            if ("DECLINED".equals(verdict)) declined.add(requestId);
        });
        return declined;
    }

    private String str(Map<Object, Object> raw, String key) {
        Object value = raw.get(key);
        return value == null ? null : String.valueOf(value);
    }
}

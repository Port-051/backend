package com.port051.queuemate.matching;

import tools.jackson.databind.ObjectMapper;
import com.port051.queuemate.contract.MatchRequestPayload;
import com.port051.queuemate.contract.Queue;
import com.port051.queuemate.contract.RedisKeys;
import com.port051.queuemate.contract.RequestState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 대기 명단과 주문 메모. 계약(05 1절)이 정한 세 키 중 둘을 다룬다.
 *
 * <p><b>명단을 Sorted Set으로 둔 이유.</b> 01 4.3이 요구하는 전순서는
 * {@code (신청 시각, 요청 ID)}다. 점수를 신청 시각으로 두면 1차 정렬이 공짜로 되고,
 * 점수가 같을 때 Redis가 member를 사전순으로 비교하므로 <b>requestId를 19자리로 제로패딩</b>하면
 * 사전순이 곧 숫자순이 된다. 두 정렬키가 자료구조 하나로 표현된다.
 *
 * <p>List로 두면 앞에서 꺼내는 것은 싸지만 취소된 요청을 중간에서 지우는 것이 O(n)이고,
 * 무엇보다 신청 시각이 같은 요청의 순서가 삽입 시점에 정해져 인스턴스마다 갈린다.
 *
 * <p><b>명단을 큐·정원 단위로 나눈 이유.</b> 01 4.1의 첫 두 제외 사유가 "큐가 다르다",
 * "목표 파티 인원이 다르다"다. 어차피 후보가 될 수 없는 것을 한 명단에 담아 매번 걸러낼 이유가 없다.
 * 02 1.4가 말한 <b>파티션 단일 라이터</b>의 파티션 경계도 이것과 같다.
 */
@Component
public class MatchRequestStore {

    private static final Logger log = LoggerFactory.getLogger(MatchRequestStore.class);

    /** {@code Long.MAX_VALUE}가 19자리다. 사전순 == 숫자순을 만드는 최소 자릿수. */
    private static final String MEMBER_FORMAT = "%019d";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RedisScript<String> cancelScript;

    public MatchRequestStore(
            StringRedisTemplate redis, ObjectMapper objectMapper, RedisScript<String> cancelRequestScript) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.cancelScript = cancelRequestScript;
    }

    public long nextRequestId() {
        Long id = redis.opsForValue().increment(RedisKeys.SEQ_REQUEST);
        return id == null ? 1L : id;
    }

    static String member(long requestId) {
        return String.format(MEMBER_FORMAT, requestId);
    }

    /** 요청을 명단에 올린다. 메모를 먼저 쓰고 명단에 넣는 순서를 지킨다. */
    public void enqueue(MatchRequestPayload payload) {
        String json = write(payload);
        redis.opsForValue().set(RedisKeys.request(payload.requestId()), json);
        redis.opsForValue().set(RedisKeys.requestState(payload.requestId()), RequestState.WAITING.name());
        redis.opsForZSet()
                .add(
                        RedisKeys.waitingQueue(payload.queue(), payload.targetSize()),
                        member(payload.requestId()),
                        payload.requestedAt());
        redis.opsForSet()
                .add(RedisKeys.userRequests(payload.userId()), String.valueOf(payload.requestId()));
    }

    /** 이 사용자가 만든 요청 id. 재연결 복구가 읽는다(02 3.4). */
    public List<Long> requestIdsOf(long userId) {
        Set<String> ids = redis.opsForSet().members(RedisKeys.userRequests(userId));
        if (ids == null || ids.isEmpty()) return List.of();
        return ids.stream().map(Long::parseLong).sorted().toList();
    }

    /**
     * 지금 시각에 확정 파티로 묶여 있는 사용자. 01 4.1 —
     * "이미 시간이 겹치는 확정 파티에 속해 있다"를 후보 단계에서 걸러낸다.
     *
     * <p><b>이건 사전 검증이라 경합에서 누락이 생긴다.</b> 여기를 통과한 뒤 확정 직전에
     * 다른 파티가 먼저 확정되면 겹침이 생기는데, 그것을 실제로 막는 것은
     * {@code confirm_party.lua}의 검사다(02 2장이 "사전 검증은 경합 조건에서 누락이 발생하고
     * 배제 제약은 발생하지 않는다"고 한 그대로다). 이 메서드의 역할은 정합성이 아니라
     * <b>가망 없는 후보로 파티를 짜다 확정 단계에서 통째로 버리는 낭비를 줄이는 것</b>이다.
     */
    public Set<Long> busyUserIds(Collection<Long> userIds, long at) {
        Set<Long> busy = new java.util.HashSet<>();
        for (long userId : userIds) {
            Long count = redis.opsForZSet().count(RedisKeys.busy(userId), at + 1, Double.MAX_VALUE);
            if (count != null && count > 0) busy.add(userId);
        }
        return busy;
    }

    /** 확정된 파티 id. 상태 조회에서 쓴다. */
    public Optional<Long> partyOf(long requestId) {
        String value = redis.opsForValue().get(RedisKeys.member(requestId));
        return value == null ? Optional.empty() : Optional.of(Long.parseLong(value));
    }

    /**
     * 명단 앞에서 {@code limit}명을 읽는다. 순서가 곧 01 4.3의 전순서다.
     *
     * <p>통째로 읽지 않고 앞에서 자르는 이유는 대기자가 수천이어도 한 틱에 성립시킬 파티가
     * 몇 개 안 되기 때문이다. 뒤쪽까지 읽어봐야 이번 틱에는 쓰이지 않는다.
     * 다만 그만큼 <b>뒤에 있는 요청은 앞이 비워질 때까지 기다린다</b> — 대기 시간과 처리량의
     * 교환이고, {@code batchSize}가 판정일의 비교 축이다.
     */
    public List<Long> waitingIds(Queue queue, int targetSize, int limit) {
        Set<String> members =
                redis.opsForZSet().range(RedisKeys.waitingQueue(queue, targetSize), 0, limit - 1L);
        if (members == null || members.isEmpty()) return List.of();
        List<Long> ids = new ArrayList<>(members.size());
        for (String m : members) ids.add(Long.parseLong(m));
        return ids;
    }

    /** 메모를 한 번에 읽는다. 요청마다 왕복하면 틱 하나가 명단 길이만큼의 RTT가 된다. */
    public List<MatchRequestPayload> loadAll(Collection<Long> requestIds) {
        if (requestIds.isEmpty()) return List.of();
        List<String> keys = requestIds.stream().map(RedisKeys::request).toList();
        List<String> values = redis.opsForValue().multiGet(keys);
        if (values == null) return List.of();

        List<MatchRequestPayload> payloads = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null) continue; // 취소·만료로 메모가 지워진 요청. 그냥 넘어간다(05 3절)
            read(value).ifPresent(payloads::add);
        }
        return payloads;
    }

    public Optional<MatchRequestPayload> load(long requestId) {
        String value = redis.opsForValue().get(RedisKeys.request(requestId));
        return value == null ? Optional.empty() : read(value);
    }

    public RequestState state(long requestId) {
        String value = redis.opsForValue().get(RedisKeys.requestState(requestId));
        return value == null ? null : RequestState.valueOf(value);
    }

    public void setState(long requestId, RequestState state) {
        redis.opsForValue().set(RedisKeys.requestState(requestId), state.name());
    }

    public Optional<Long> currentOffer(long requestId) {
        String value = redis.opsForValue().get(RedisKeys.requestOffer(requestId));
        return value == null ? Optional.empty() : Optional.of(Long.parseLong(value));
    }

    /**
     * 취소. 01 3.9 — 취소와 제안 생성이 동시에 일어나면 상태 전이에 먼저 성공한 쪽이 이긴다.
     *
     * @return 비어 있으면 취소 성공. 값이 있으면 제안이 먼저 선점했고 그 제안 id다
     */
    public CancelResult cancel(long requestId, Queue queue, int targetSize) {
        String result =
                redis.execute(
                        cancelScript,
                        List.of(
                                RedisKeys.waitingQueue(queue, targetSize),
                                RedisKeys.requestState(requestId),
                                RedisKeys.request(requestId),
                                RedisKeys.claim(requestId),
                                RedisKeys.requestOffer(requestId)),
                        String.valueOf(requestId));

        if (result == null) return CancelResult.ofNotWaiting(null);
        if (result.startsWith("OFFERED:")) {
            String offerId = result.substring("OFFERED:".length());
            return CancelResult.ofLostToOffer(offerId.isEmpty() ? null : Long.parseLong(offerId));
        }
        if ("CANCELLED".equals(result)) return CancelResult.ofCancelled();
        return CancelResult.ofNotWaiting(null);
    }

    /** 최대 대기시간 초과. 01 10.2 — 명단에서 지우고 실패로 종료한다. */
    public void fail(MatchRequestPayload payload) {
        redis.opsForZSet()
                .remove(
                        RedisKeys.waitingQueue(payload.queue(), payload.targetSize()),
                        member(payload.requestId()));
        redis.opsForValue().set(RedisKeys.requestState(payload.requestId()), RequestState.FAILED.name());
        redis.delete(RedisKeys.request(payload.requestId()));
        redis.delete(RedisKeys.requestOffer(payload.requestId()));
    }

    /** 제안이 깨진 뒤 재대기. 01 5.3 — <b>기존 대기 순서를 유지한다</b>. */
    public void requeue(MatchRequestPayload payload) {
        redis.opsForValue().set(RedisKeys.requestState(payload.requestId()), RequestState.WAITING.name());
        redis.delete(RedisKeys.requestOffer(payload.requestId()));
        // 점수를 원래 requestedAt으로 다시 넣는다. 지금 시각으로 넣으면 맨 뒤로 밀려
        // "정상 이용자는 기존 대기 순서를 유지한 채 다시 대기한다"가 깨진다.
        redis.opsForZSet()
                .add(
                        RedisKeys.waitingQueue(payload.queue(), payload.targetSize()),
                        member(payload.requestId()),
                        payload.requestedAt());
    }

    /** 제안에 묶인 동안은 명단에서 뺀다. 다른 매처가 같은 요청을 다시 집지 않게 한다. */
    public void detachForOffer(MatchRequestPayload payload, long offerId) {
        redis.opsForZSet()
                .remove(
                        RedisKeys.waitingQueue(payload.queue(), payload.targetSize()),
                        member(payload.requestId()));
        redis.opsForValue().set(RedisKeys.requestState(payload.requestId()), RequestState.OFFERED.name());
        redis.opsForValue().set(RedisKeys.requestOffer(payload.requestId()), String.valueOf(offerId));
    }

    public Set<Long> userIdsOf(Collection<MatchRequestPayload> payloads) {
        Set<Long> userIds = new LinkedHashSet<>();
        payloads.forEach(p -> userIds.add(p.userId()));
        return userIds;
    }

    private String write(MatchRequestPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("요청 직렬화 실패 requestId=" + payload.requestId(), e);
        }
    }

    private Optional<MatchRequestPayload> read(String json) {
        try {
            return Optional.of(objectMapper.readValue(json, MatchRequestPayload.class));
        } catch (Exception e) {
            log.warn("요청 역직렬화 실패 — 건너뛴다", e);
            return Optional.empty();
        }
    }

    /**
     * 취소 시도의 결과. 01 3.9의 세 갈래를 그대로 옮긴 것이다.
     *
     * @param cancelled 취소에 성공했는가
     * @param offerId   제안이 먼저 선점했다면 그 제안 id. 사용자에게 거절 경로를 제시할 때 쓴다
     */
    public record CancelResult(boolean cancelled, Long offerId) {
        static CancelResult ofCancelled() {
            return new CancelResult(true, null);
        }

        static CancelResult ofLostToOffer(Long offerId) {
            return new CancelResult(false, offerId);
        }

        static CancelResult ofNotWaiting(Long offerId) {
            return new CancelResult(false, offerId);
        }
    }
}

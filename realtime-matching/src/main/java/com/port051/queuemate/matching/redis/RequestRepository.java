package com.port051.queuemate.matching.redis;

import com.port051.queuemate.matching.domain.MatchRequestView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 대기 명단(ZSET)과 주문 메모(STRING JSON). 실시간 매칭이 읽는 전부다. */
@Component
public class RequestRepository {

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final RedisScript<Long> cancelScript;

    public RequestRepository(StringRedisTemplate redis, ObjectMapper json, RedisScript<Long> cancelScript) {
        this.redis = redis;
        this.json = json;
        this.cancelScript = cancelScript;
    }

    public long nextRequestId() {
        Long id = redis.opsForValue().increment(Keys.SEQ_REQUEST);
        return id == null ? 0L : id;
    }

    /** 요청을 대기 명단에 올린다. score가 곧 FCFS의 1차 정렬키다. */
    public void enqueue(MatchRequestView request) {
        String partition = Keys.partition(request.queue(), request.targetSize());
        // 메모의 TTL은 최대 대기시간보다 넉넉히 둔다. 만료 판정은 메모의 expiresAt으로 하고
        // TTL은 매칭 루프가 죽어 있는 동안 명단이 무한히 자라지 않게 하는 백스톱이다.
        Duration ttl = Duration.ofMillis(Math.max(60_000, request.expiresAt() - request.requestedAt()))
                .plusMinutes(10);
        redis.opsForValue().set(Keys.request(request.requestId()), json.writeValueAsString(request), ttl);
        redis.opsForZSet().add(partition, String.valueOf(request.requestId()), request.requestedAt());
        redis.opsForSet().add(Keys.PARTITIONS, partition);
        redis.opsForSet().add(Keys.userRequests(request.userId()), String.valueOf(request.requestId()));
    }

    public Optional<MatchRequestView> find(long requestId) {
        String payload = redis.opsForValue().get(Keys.request(requestId));
        return Optional.ofNullable(payload).map(p -> json.readValue(p, MatchRequestView.class));
    }

    public List<String> partitions() {
        Set<String> members = redis.opsForSet().members(Keys.PARTITIONS);
        return members == null ? List.of() : members.stream().sorted().toList();
    }

    public long waitingCount(String partition) {
        Long count = redis.opsForZSet().zCard(partition);
        return count == null ? 0L : count;
    }

    /**
     * 대기 명단을 앞에서부터 훑어 메모를 읽는다.
     * 메모가 사라진 항목은 명단에서 지운다 — "명단에 없네" 하고 넘어가면 끝이다.
     */
    public List<MatchRequestView> scan(String partition, int limit) {
        Set<String> ids = redis.opsForZSet().range(partition, 0, limit - 1);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<String> ordered = List.copyOf(ids);
        List<String> keys = ordered.stream().map(id -> Keys.request(Long.parseLong(id))).toList();
        List<String> payloads = redis.opsForValue().multiGet(keys);

        List<MatchRequestView> live = new ArrayList<>(ordered.size());
        List<String> stale = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            String payload = payloads == null ? null : payloads.get(i);
            if (payload == null) {
                stale.add(ordered.get(i));
            } else {
                live.add(json.readValue(payload, MatchRequestView.class));
            }
        }
        if (!stale.isEmpty()) {
            redis.opsForZSet().remove(partition, stale.toArray());
        }
        // 01 4.3 — 결정적 FCFS. ZSET은 score 순만 보장하므로 동시각 타이를 요청 ID로 확정한다.
        return live.stream().sorted(MatchRequestView.FCFS).toList();
    }

    /** 01 3.9 — 취소. 이미 제안이 선점했으면 false. 호출자가 거절 경로를 안내한다. */
    public boolean cancel(MatchRequestView request) {
        Long result = redis.execute(cancelScript,
                List.of(Keys.request(request.requestId()),
                        Keys.claim(request.requestId()),
                        Keys.partition(request.queue(), request.targetSize()),
                        Keys.userRequests(request.userId())),
                String.valueOf(request.requestId()));
        return result != null && result == 1L;
    }

    /** 01 10.2 — 최대 대기시간 만료. 명단에서 내리고 메모를 지운다. */
    public void expire(MatchRequestView request) {
        redis.opsForZSet().remove(Keys.partition(request.queue(), request.targetSize()),
                String.valueOf(request.requestId()));
        redis.delete(Keys.request(request.requestId()));
        redis.opsForSet().remove(Keys.userRequests(request.userId()), String.valueOf(request.requestId()));
    }

    public List<Long> requestIdsOf(long userId) {
        Set<String> ids = redis.opsForSet().members(Keys.userRequests(userId));
        return ids == null ? List.of() : ids.stream().map(Long::parseLong).sorted().toList();
    }

    /** 이미 선점된 요청. 매칭 루프가 후보 풀에서 먼저 걷어낸다. */
    public Set<Long> claimedAmong(List<Long> requestIds) {
        if (requestIds.isEmpty()) {
            return Set.of();
        }
        List<String> keys = requestIds.stream().map(Keys::claim).toList();
        List<String> values = redis.opsForValue().multiGet(keys);
        Set<Long> claimed = new java.util.LinkedHashSet<>();
        for (int i = 0; i < requestIds.size(); i++) {
            if (values != null && values.get(i) != null) {
                claimed.add(requestIds.get(i));
            }
        }
        return claimed;
    }

    /** 선점 중인 제안 ID. 없으면 대기 중이라는 뜻이다. */
    public Optional<Long> claimedBy(long requestId) {
        return Optional.ofNullable(redis.opsForValue().get(Keys.claim(requestId))).map(Long::parseLong);
    }
}

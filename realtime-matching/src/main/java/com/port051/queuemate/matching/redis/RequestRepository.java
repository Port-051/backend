package com.port051.queuemate.matching.redis;

import com.port051.queuemate.matching.domain.MatchRequestView;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 대기 명단(ZSET)과 주문 메모(STRING JSON)에 요청을 적재하고 되읽는다.
 *
 * <p><b>명단을 훑는 것은 여기에 없다.</b> 대기 명단을 어떻게 읽느냐 — 한 번에 몇 개를 볼지,
 * 파이프라인으로 묶을지, 커서로 나눌지 — 가 처리량 축이라 비교 대상이다. 선점 상태 조회,
 * 취소, 만료도 같은 이유로 각자 짠다. 취소·만료·확정이 명단에서 이름을 지우는 방식은
 * 선점을 어떻게 표현하느냐에 통째로 딸려 있다.
 *
 * <p>여기 남은 것은 <b>메모의 적재 형태와 상태 조회</b>다. 메모의 모양이 다르면 같은 k6
 * 스크립트를 쓸 수 없고, 상태 조회는 API 계약이다(규약 10절).
 */
@Component
public class RequestRepository {

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public RequestRepository(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
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

    /** 01 0.1 — 조건 입력 화면에 현재 대기 인원을 표시한다. */
    public long waitingCount(String partition) {
        Long count = redis.opsForZSet().zCard(partition);
        return count == null ? 0L : count;
    }

    /** 02 3.4 — 재연결 시 현재 상태를 다시 조회하기 위한 사용자별 인덱스. */
    public List<Long> requestIdsOf(long userId) {
        Set<String> ids = redis.opsForSet().members(Keys.userRequests(userId));
        return ids == null ? List.of() : ids.stream().map(Long::parseLong).sorted().toList();
    }
}

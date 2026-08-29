package com.port051.queuemate.matching.store;

import com.port051.queuemate.matching.domain.GameQueue;
import com.port051.queuemate.matching.domain.MatchRequest;
import com.port051.queuemate.matching.domain.Partition;
import com.port051.queuemate.matching.domain.Position;
import com.port051.queuemate.matching.domain.Purpose;
import com.port051.queuemate.matching.domain.VoiceMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** 01-functional-spec-mvp 10.2 즉시 매칭 만료. */
@SpringBootTest
@Testcontainers
class RequestExpiryTest {

    @Container
    @ServiceConnection
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Autowired
    RequestExpiry expiry;

    private static final Partition PARTITION = new Partition(GameQueue.SOLO_DUO, 2);

    @Autowired
    ClaimStore claims;

    @Autowired
    WaitingList waitingList;

    @Autowired
    RequestStore requestStore;

    @Autowired
    RedisClock clock;

    @Autowired
    StringRedisTemplate strings;

    @BeforeEach
    void clear() {
        strings.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    /** 신청 시각을 직접 지정해 요청을 만든다. */
    private static MatchRequest requestAt(long requestId, long requestedAt) {
        return new MatchRequest(
                requestId, requestId,
                GameQueue.SOLO_DUO, 2, Purpose.RANK_UP, 120, VoiceMode.POSSIBLE,
                Position.MID, List.of(),
                14, 11, 18,
                requestedAt);
    }

    /**
     * 지금으로부터 {@code agoMillis} 전에 신청한 요청을 만든다.
     *
     * <p>부를 때마다 시계를 다시 읽으므로 두 요청의 신청 시각이 같지 않다.
     * 동률을 만들려면 {@link #requestAt}에 같은 값을 넘겨야 한다.
     */
    private MatchRequest requestFrom(long requestId, long agoMillis) {
        return requestAt(requestId, clock.nowMillis() - agoMillis);
    }

    private void register(MatchRequest request) {
        requestStore.save(request);
        waitingList.add(request);
    }

    @Test
    @DisplayName("최대 대기시간이 지난 요청을 종료한다")
    void expiresRequestsPastTheMaxWait() {
        register(requestFrom(1L, expiry.maxWait().toMillis() + 1_000));

        assertThat(expiry.sweep()).containsExactly(1L);
    }

    @Test
    @DisplayName("아직 대기 중인 요청은 건드리지 않는다")
    void keepsRequestsStillWithinTheMaxWait() {
        register(requestFrom(1L, 1_000));

        assertThat(expiry.sweep()).isEmpty();
        assertThat(waitingList.requestIds(PARTITION)).containsExactly(1L);
        assertThat(requestStore.find(1L)).isPresent();
    }

    @Test
    @DisplayName("만료된 것만 골라 지운다")
    void expiresOnlyTheOldOnes() {
        long maxWait = expiry.maxWait().toMillis();
        register(requestFrom(1L, maxWait + 10_000));
        register(requestFrom(2L, maxWait + 1_000));
        register(requestFrom(3L, 1_000));

        assertThat(expiry.sweep()).containsExactly(1L, 2L);
        assertThat(waitingList.requestIds(PARTITION)).containsExactly(3L);
    }

    @Test
    @DisplayName("명단과 메모를 함께 지운다")
    void removesFromBothTheListAndTheStore() {
        register(requestFrom(1L, expiry.maxWait().toMillis() + 1_000));

        expiry.sweep();

        assertThat(waitingList.requestIds(PARTITION)).isEmpty();
        assertThat(requestStore.find(1L)).isEmpty();
    }

    @Test
    @DisplayName("만료된 것이 없으면 아무것도 하지 않는다")
    void doesNothingWhenNothingExpired() {
        register(requestFrom(1L, 1_000));

        assertThat(expiry.sweep()).isEmpty();
        assertThat(waitingList.size(PARTITION)).isEqualTo(1);
    }

    @Test
    @DisplayName("빈 명단에서도 문제없다")
    void handlesAnEmptyList() {
        assertThat(expiry.sweep()).isEmpty();
    }

    @Test
    @DisplayName("두 번 쓸어도 결과가 같다")
    void sweepingTwiceIsIdempotent() {
        register(requestFrom(1L, expiry.maxWait().toMillis() + 1_000));

        assertThat(expiry.sweep()).containsExactly(1L);
        assertThat(expiry.sweep()).isEmpty();
    }

    @Test
    @DisplayName("메모에 유지 시간이 걸려 있다")
    void requestMemosCarryATtl() {
        register(requestFrom(1L, 0));

        Long remaining = strings.getExpire("req:1", java.util.concurrent.TimeUnit.MILLISECONDS);

        assertThat(remaining).isPositive();
        // 최대 대기시간보다 길어야 대기 중인 요청의 조건이 먼저 사라지지 않는다.
        assertThat(requestStore.ttl()).isGreaterThan(expiry.maxWait());
    }

    @Test
    @DisplayName("배정 중인 요청은 만료시키지 않는다")
    void neverExpiresARequestBeingConfirmed() {
        long expiredAt = clock.nowMillis() - expiry.maxWait().toMillis() - 1_000;
        register(requestAt(1L, expiredAt));
        register(requestAt(2L, expiredAt));
        // 다른 인스턴스가 1번으로 파티를 확정하는 중이다.
        claims.claimAll(List.of(1L));

        assertThat(expiry.sweep()).containsExactly(2L);
        // 1번은 명단에 남는다. 확정되면 거기서 빠지고, 실패하면 다음 바퀴에 만료된다.
        assertThat(waitingList.requestIds(PARTITION)).containsExactly(1L);
        assertThat(requestStore.find(1L)).isPresent();
    }

    @Test
    @DisplayName("동시에 쓸어도 한 쪽만 만료 목록을 받는다")
    void onlyOneSweeperClaimsTheExpiredRequests() throws Exception {
        long expiredAt = clock.nowMillis() - expiry.maxWait().toMillis() - 1_000;
        for (long id = 1; id <= 10; id++) {
            register(requestAt(id, expiredAt));
        }

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<List<Long>>> sweeps = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                sweeps.add(pool.submit(() -> {
                    start.await();
                    return expiry.sweep();
                }));
            }
            start.countDown();

            List<Long> reported = new ArrayList<>();
            for (Future<List<Long>> sweep : sweeps) {
                reported.addAll(sweep.get());
            }

            // 같은 요청을 두 번 만료시켰다면 사용자에게 소식도 두 번 간다.
            assertThat(reported).doesNotHaveDuplicates();
            assertThat(reported).hasSize(10);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("만료 대상이 전순서대로 나온다")
    void reportsExpiredRequestsInOrder() {
        long expiredAt = clock.nowMillis() - expiry.maxWait().toMillis() - 1_000;
        register(requestAt(10L, expiredAt));
        register(requestAt(9L, expiredAt));

        // 신청 시각이 정확히 같으므로 요청 ID로 갈린다.
        assertThat(expiry.sweep()).containsExactly(9L, 10L);
    }

    @Test
    @DisplayName("경계에 걸친 요청도 만료된다")
    void expiresRequestsExactlyAtTheBoundary() {
        register(requestAt(1L, clock.nowMillis() - expiry.maxWait().toMillis()));

        assertThat(expiry.sweep()).containsExactly(1L);
    }
}

package com.port051.queuemate.matching.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.port051.queuemate.matching.api.dto.CreateMatchRequest;
import com.port051.queuemate.matching.domain.ComposedParty;
import com.port051.queuemate.matching.domain.GameQueue;
import com.port051.queuemate.matching.domain.MatchRequestView;
import com.port051.queuemate.matching.domain.Position;
import com.port051.queuemate.matching.domain.PositionAssigner;
import com.port051.queuemate.matching.domain.Purpose;
import com.port051.queuemate.matching.domain.VoiceMode;
import com.port051.queuemate.matching.intake.MatchRequestService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 좌석 선점의 유일한 안전장치가 Lua 원자 선점 하나라는 것을 확인한다.
 * 같은 사람을 포함한 두 조합이 동시에 선점을 시도하면 <b>하나만</b> 성립해야 한다.
 *
 * <p>이 스파이크에는 DB 제약이 없다. 그래서 이 테스트가 깨지면 정원 초과(INV-1)와
 * 중복 배정(INV-2)을 막는 것이 아무것도 남지 않는다.
 */
@SpringBootTest(properties = {
        "matching.initial-delay=1h",   // 매칭 루프가 테스트 데이터를 집어가지 않게 재운다
        "matching.cycle-interval=1h"
})
@Testcontainers(disabledWithoutDocker = true)
class ClaimContentionTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    MatchRequestService requestService;
    @Autowired
    RequestRepository requests;
    @Autowired
    ClaimService claims;
    @Autowired
    StringRedisTemplate redis;

    @Test
    @DisplayName("같은 멤버를 포함한 두 조합이 동시에 선점하면 하나만 성립한다")
    void onlyOneClaimWins() throws Exception {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();

        List<MatchRequestView> pool = IntStream.range(0, 9)
                .mapToObj(i -> requestService.create(new CreateMatchRequest(
                        (long) (i + 1), GameQueue.FLEX, 5, Purpose.RANK_UP, 120, 30,
                        VoiceMode.POSSIBLE, Position.values()[i % 5],
                        List.of(), 14, 10, 18)))
                .toList();

        // 0~4번과 4~8번 — 5번째 멤버(index 4)를 공유하는 두 조합
        ComposedParty first = party(pool.subList(0, 5));
        ComposedParty second = party(pool.subList(4, 9));

        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService pool2 = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> attemptFirst = () -> {
                barrier.await();
                return claims.claim(first, 1001L, Duration.ofSeconds(30));
            };
            Callable<Boolean> attemptSecond = () -> {
                barrier.await();
                return claims.claim(second, 1002L, Duration.ofSeconds(30));
            };
            List<Future<Boolean>> results = pool2.invokeAll(List.of(attemptFirst, attemptSecond));

            long won = results.stream().filter(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).count();
            assertThat(won).isEqualTo(1);
        }

        // 진 쪽은 잔여물을 남기지 않는다 — 부분 선점이 남으면 다음 사이클이 영영 막힌다
        long claimed = pool.stream()
                .filter(request -> requests.claimedBy(request.requestId()).isPresent())
                .count();
        assertThat(claimed).isEqualTo(5);
    }

    @Test
    @DisplayName("01 3.9 — 선점된 요청은 취소할 수 없다. 취소된 요청은 선점할 수 없다")
    void cancelAndClaimRaceHasOneWinner() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();

        List<MatchRequestView> pool = IntStream.range(0, 5)
                .mapToObj(i -> requestService.create(new CreateMatchRequest(
                        (long) (i + 1), GameQueue.FLEX, 5, Purpose.RANK_UP, 120, 30,
                        VoiceMode.POSSIBLE, Position.values()[i], List.of(), 14, 10, 18)))
                .toList();

        assertThat(requests.cancel(pool.getFirst())).isTrue();
        // 명단에서 빠졌으므로 선점이 성립하지 않는다
        assertThat(claims.claim(party(pool), 2001L, Duration.ofSeconds(30))).isFalse();

        List<MatchRequestView> remaining = pool.subList(1, 5);
        ComposedParty four = party(remaining);
        assertThat(claims.claim(four, 2002L, Duration.ofSeconds(30))).isTrue();
        // 선점된 요청의 취소는 실패한다 — 제안이 이겼다
        assertThat(requests.cancel(remaining.getFirst())).isFalse();
    }

    private static ComposedParty party(List<MatchRequestView> members) {
        return new ComposedParty(List.copyOf(members), PositionAssigner.assign(members).orElseThrow());
    }
}

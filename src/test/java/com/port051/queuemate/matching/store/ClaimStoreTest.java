package com.port051.queuemate.matching.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 05-realtime-matching-contract 3장 — 전원 아니면 아무도 아니다. */
@SpringBootTest
@Testcontainers
class ClaimStoreTest {

    @Container
    @ServiceConnection
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Autowired
    ClaimStore claims;

    @Autowired
    StringRedisTemplate strings;

    @BeforeEach
    void clear() {
        strings.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    private static final List<Long> PARTY = List.of(1L, 2L, 3L, 4L, 5L);

    @Test
    @DisplayName("아무도 안 잡혀 있으면 전원을 잡는다")
    void claimsEveryoneWhenAllAreFree() {
        assertThat(claims.claimAll(PARTY)).isPresent();

        for (long requestId : PARTY) {
            assertThat(claims.isClaimed(requestId)).isTrue();
        }
    }

    @Test
    @DisplayName("성공하면 소유자 토큰을 준다")
    void handsBackAnOwnerToken() {
        Optional<String> owner = claims.claimAll(PARTY);

        assertThat(owner).isPresent();
        assertThat(owner.get()).isNotBlank();
    }

    @Test
    @DisplayName("시도할 때마다 다른 토큰이다")
    void everyAttemptGetsItsOwnToken() {
        String first = claims.claimAll(List.of(1L)).orElseThrow();
        claims.releaseAll(List.of(1L), first);

        String second = claims.claimAll(List.of(1L)).orElseThrow();

        assertThat(second).isNotEqualTo(first);
    }

    @Nested
    @DisplayName("전원 아니면 아무도")
    class AllOrNothing {

        @Test
        @DisplayName("하나라도 이미 잡혀 있으면 실패한다")
        void failsWhenAnyoneIsAlreadyClaimed() {
            claims.claimAll(List.of(3L));

            assertThat(claims.claimAll(PARTY)).isEmpty();
        }

        @Test
        @DisplayName("실패하면 부분적으로 잡힌 것이 남지 않는다")
        void leavesNothingBehindOnFailure() {
            claims.claimAll(List.of(3L));

            claims.claimAll(PARTY);

            // 3번은 원래 주인 것이고, 나머지 넷은 아무도 잡지 않았어야 한다.
            assertThat(claims.isClaimed(1L)).isFalse();
            assertThat(claims.isClaimed(2L)).isFalse();
            assertThat(claims.isClaimed(3L)).isTrue();
            assertThat(claims.isClaimed(4L)).isFalse();
            assertThat(claims.isClaimed(5L)).isFalse();
        }

        @Test
        @DisplayName("막힌 것이 마지막이어도 앞의 것들이 남지 않는다")
        void leavesNothingBehindWhenTheLastOneIsTaken() {
            claims.claimAll(List.of(5L));

            claims.claimAll(PARTY);

            assertThat(claims.isClaimed(1L)).isFalse();
            assertThat(claims.isClaimed(4L)).isFalse();
        }

        @Test
        @DisplayName("두 번째 시도는 겹치는 사람이 없으면 성공한다")
        void succeedsWhenNobodyOverlaps() {
            claims.claimAll(List.of(1L, 2L));

            assertThat(claims.claimAll(List.of(3L, 4L))).isPresent();
        }
    }

    @Nested
    @DisplayName("해제")
    class Release {

        @Test
        @DisplayName("떼고 나면 다시 잡을 수 있다")
        void releasedRequestsCanBeClaimedAgain() {
            String owner = claims.claimAll(PARTY).orElseThrow();

            claims.releaseAll(PARTY, owner);

            assertThat(claims.isClaimed(1L)).isFalse();
            assertThat(claims.claimAll(PARTY)).isPresent();
        }

        @Test
        @DisplayName("뗀 개수를 돌려준다")
        void reportsHowManyItReleased() {
            String owner = claims.claimAll(PARTY).orElseThrow();

            assertThat(claims.releaseAll(PARTY, owner)).isEqualTo(5);
        }

        @Test
        @DisplayName("남의 토큰으로는 떼지 못한다")
        void neverReleasesSomeoneElsesClaim() {
            claims.claimAll(PARTY);

            long released = claims.releaseAll(PARTY, "남의-토큰");

            assertThat(released).isZero();
            assertThat(claims.isClaimed(1L)).isTrue();
        }

        @Test
        @DisplayName("이미 풀린 것을 떼도 문제되지 않는다")
        void releasingWhatIsAlreadyGoneIsFine() {
            String owner = claims.claimAll(PARTY).orElseThrow();
            claims.releaseAll(PARTY, owner);

            assertThat(claims.releaseAll(PARTY, owner)).isZero();
        }
    }

    @Nested
    @DisplayName("유지 시간")
    class Ttl {

        @Test
        @DisplayName("표시에 유지 시간이 걸려 있다")
        void claimsExpireOnTheirOwn() {
            claims.claimAll(List.of(1L));

            Long remaining = strings.getExpire("claim:1", TimeUnit.MILLISECONDS);

            assertThat(remaining).isPositive().isLessThanOrEqualTo(claims.ttl().toMillis());
        }

        @Test
        @DisplayName("유지 시간이 지나면 저절로 풀린다")
        void expiredClaimsAreGone() {
            claims.claimAll(List.of(1L));

            // TTL이 끝난 상황을 흉내낸다. 기다리는 대신 표시를 직접 지운다.
            strings.delete("claim:1");

            assertThat(claims.isClaimed(1L)).isFalse();
            assertThat(claims.claimAll(List.of(1L))).isPresent();
        }
    }

    @Test
    @DisplayName("빈 목록은 잡지 않는다")
    void claimingNothingNeverSucceeds() {
        assertThat(claims.claimAll(List.of())).isEmpty();
        assertThat(claims.releaseAll(List.of(), "아무거나")).isZero();
    }

    @Test
    @DisplayName("여러 인스턴스가 동시에 같은 파티를 노려도 한 쪽만 잡는다")
    void onlyOneWinnerWhenEveryoneRacesForTheSameParty() throws Exception {
        int racers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Optional<String>>> attempts = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                attempts.add(pool.submit(() -> {
                    start.await();
                    return claims.claimAll(PARTY);
                }));
            }
            start.countDown();

            long winners = 0;
            for (Future<Optional<String>> attempt : attempts) {
                if (attempt.get().isPresent()) {
                    winners++;
                }
            }

            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("겹치는 파티를 동시에 노려도 부분적으로 잡힌 것이 남지 않는다")
    void overlappingPartiesNeverDeadlockEachOther() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            // 3번을 공유하는 두 파티. 하나씩 잡는 방식이었다면 서로 일부만 쥔 채 막힐 수 있다.
            Future<Optional<String>> left = pool.submit(() -> {
                start.await();
                return claims.claimAll(List.of(1L, 2L, 3L));
            });
            Future<Optional<String>> right = pool.submit(() -> {
                start.await();
                return claims.claimAll(List.of(3L, 4L, 5L));
            });
            start.countDown();

            boolean leftWon = left.get().isPresent();
            boolean rightWon = right.get().isPresent();

            assertThat(leftWon ^ rightWon).as("정확히 한 쪽만 이겨야 한다").isTrue();
            if (leftWon) {
                assertThat(claims.isClaimed(4L)).isFalse();
                assertThat(claims.isClaimed(5L)).isFalse();
            } else {
                assertThat(claims.isClaimed(1L)).isFalse();
                assertThat(claims.isClaimed(2L)).isFalse();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("같은 요청이 목록에 두 번 있어도 잡힌다")
    void toleratesDuplicateRequestIds() {
        assertThat(claims.claimAll(List.of(1L, 1L))).isPresent();
    }
}

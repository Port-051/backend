package com.port051.queuemate.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.port051.queuemate.contract.MatchRequestPayload;
import com.port051.queuemate.contract.Position;
import com.port051.queuemate.contract.RedisKeys;
import com.port051.queuemate.result.PartyRecorder;
import com.port051.queuemate.support.Requests;
import com.port051.queuemate.support.RedisTestBase;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Lua 스크립트가 실제 Redis에서 무엇을 막는지 확인한다.
 *
 * <p>02 2장은 검출 질의의 역할이 "위반을 찾는 것이 아니라 <b>제약이 실제로 걸려 있고 동작하는지
 * 확인</b>하는 것"이라고 했다. 이 테스트가 그 역할이다 — DB 제약 대신 Lua가 그 자리에 있으므로,
 * 그것이 정말 막는지를 여기서 본다.
 */
class RedisScriptTest extends RedisTestBase {

    @Autowired ClaimManager claims;
    @Autowired PartyRecorder parties;

    @Test
    @DisplayName("선점은 all-or-nothing이다 — 하나라도 잡혀 있으면 아무것도 잡지 않는다")
    void claimIsAllOrNothing() {
        assertThat(claims.claimAll(List.of(1L, 2L, 3L), "first")).isTrue();

        // 3번이 겹친다. 4·5번은 비어 있지만 이 호출은 통째로 실패해야 한다.
        assertThat(claims.claimAll(List.of(3L, 4L, 5L), "second")).isFalse();

        assertThat(claims.isClaimed(4L)).isFalse();
        assertThat(claims.isClaimed(5L)).isFalse();
    }

    @Test
    @DisplayName("동시에 같은 후보를 선점하면 정확히 하나만 이긴다")
    void concurrentClaimHasExactlyOneWinner() throws Exception {
        int threads = 32;
        List<Long> contested = List.of(10L, 11L, 12L, 13L, 14L);

        AtomicInteger winners = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                String token = "matcher-" + i;
                pool.submit(() -> {
                    try {
                        start.await();
                        if (claims.claimAll(contested, token)) winners.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(winners.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("내 선점만 뗀다 — 남의 토큰이 붙은 키는 건드리지 않는다")
    void releaseOnlyRemovesOwnClaims() {
        claims.claimAll(List.of(20L), "mine");
        claims.claimAll(List.of(21L), "theirs");

        claims.release(List.of(20L, 21L), "mine");

        assertThat(claims.isClaimed(20L)).isFalse();
        assertThat(claims.isClaimed(21L)).isTrue(); // 남의 것은 남아 있다
    }

    @Test
    @DisplayName("INV-3 — 이미 배정된 요청이 있으면 확정이 통째로 막힌다")
    void confirmBlocksAlreadyAssignedRequest() {
        List<MatchRequestPayload> party =
                List.of(Requests.at(1, Position.TOP), Requests.at(2, Position.MID));
        Map<Long, Position> positions = Map.of(1L, Position.TOP, 2L, Position.MID);

        assertThat(parties.confirm(party, positions, System.currentTimeMillis()).isConfirmed())
                .isTrue();

        // 2번을 다른 사람과 묶어 또 확정하려 한다. member:2가 이미 있으므로 막혀야 한다.
        List<MatchRequestPayload> overlapping =
                List.of(Requests.at(2, Position.MID), Requests.at(3, Position.JUNGLE));
        PartyRecorder.ConfirmResult second =
                parties.confirm(
                        overlapping,
                        Map.of(2L, Position.MID, 3L, Position.JUNGLE),
                        System.currentTimeMillis());

        assertThat(second.isConfirmed()).isFalse();
        assertThat(second.blockedBy()).contains("INV-3");
        // 부분 반영이 없어야 한다 — 3번은 어느 파티에도 들어가지 않았다.
        assertThat(redis.hasKey(RedisKeys.member(3L))).isFalse();
        assertThat(redis.opsForSet().size(RedisKeys.PARTY_INDEX)).isEqualTo(1);
    }

    @Test
    @DisplayName("INV-4 — 파티 안에서 포지션이 겹치면 확정하지 않는다")
    void confirmBlocksDuplicatePosition() {
        List<MatchRequestPayload> party =
                List.of(Requests.at(1, Position.MID), Requests.at(2, Position.MID));

        PartyRecorder.ConfirmResult result =
                parties.confirm(
                        party,
                        Map.of(1L, Position.MID, 2L, Position.MID),
                        System.currentTimeMillis());

        assertThat(result.isConfirmed()).isFalse();
        assertThat(result.blockedBy()).contains("INV-4");
        assertThat(redis.opsForSet().size(RedisKeys.PARTY_INDEX)).isZero();
    }

    @Test
    @DisplayName("INV-2 — 시간이 겹치는 파티에 같은 사람을 두 번 넣지 않는다")
    void confirmBlocksOverlappingParty() {
        long now = System.currentTimeMillis();

        // 1번 사용자가 90분짜리 파티에 들어간다 (Requests.at의 playMinutes = 120).
        List<MatchRequestPayload> first =
                List.of(Requests.at(1, Position.TOP), Requests.at(2, Position.MID));
        assertThat(parties.confirm(first, Map.of(1L, Position.TOP, 2L, Position.MID), now).isConfirmed())
                .isTrue();

        // 같은 사용자(userId 1)가 새 요청(requestId 11)으로 다시 묶이려 한다.
        // requestId가 다르니 INV-3에는 안 걸리지만, 시간이 겹치므로 막혀야 한다.
        MatchRequestPayload sameUserAgain =
                Requests.of(11, 1, com.port051.queuemate.contract.Queue.FLEX, 2,
                        com.port051.queuemate.contract.Purpose.RANK_UP,
                        com.port051.queuemate.contract.VoiceMode.POSSIBLE,
                        14, 1, 28, now, Position.TOP);
        List<MatchRequestPayload> overlapping =
                List.of(sameUserAgain, Requests.at(12, Position.MID));

        PartyRecorder.ConfirmResult blocked =
                parties.confirm(
                        overlapping, Map.of(11L, Position.TOP, 12L, Position.MID), now + 1000);
        assertThat(blocked.isConfirmed()).isFalse();
        assertThat(blocked.blockedBy()).contains("INV-2");
        assertThat(redis.opsForSet().size(RedisKeys.PARTY_INDEX)).isEqualTo(1);
    }

    @Test
    @DisplayName("앞 파티가 끝난 뒤라면 같은 사람을 다시 넣는다 — INV-2는 겹칠 때만 막는다")
    void confirmAllowsNonOverlappingParty() {
        long now = System.currentTimeMillis();

        List<MatchRequestPayload> first =
                List.of(Requests.at(1, Position.TOP), Requests.at(2, Position.MID));
        assertThat(parties.confirm(first, Map.of(1L, Position.TOP, 2L, Position.MID), now).isConfirmed())
                .isTrue();

        // 앞 파티는 now + 120분에 끝난다. 그 뒤에 시작하는 파티는 겹치지 않는다.
        long afterFirstEnds = now + 121 * 60_000L;
        MatchRequestPayload sameUserLater =
                Requests.of(11, 1, com.port051.queuemate.contract.Queue.FLEX, 2,
                        com.port051.queuemate.contract.Purpose.RANK_UP,
                        com.port051.queuemate.contract.VoiceMode.POSSIBLE,
                        14, 1, 28, now, Position.TOP);

        assertThat(
                        parties.confirm(
                                        List.of(sameUserLater, Requests.at(12, Position.MID)),
                                        Map.of(11L, Position.TOP, 12L, Position.MID),
                                        afterFirstEnds)
                                .isConfirmed())
                .isTrue();
        assertThat(redis.opsForSet().size(RedisKeys.PARTY_INDEX)).isEqualTo(2);
    }

    @Test
    @DisplayName("동시에 확정을 시도해도 한 요청은 한 파티에만 들어간다 — INV-3")
    void concurrentConfirmKeepsInv3() throws Exception {
        int threads = 24;
        AtomicInteger confirmed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                // 전부 100번 요청을 포함한다. 하나만 성공해야 한다.
                long partner = 200 + i;
                pool.submit(() -> {
                    try {
                        start.await();
                        List<MatchRequestPayload> party =
                                List.of(
                                        Requests.at(100, Position.TOP),
                                        Requests.at(partner, Position.MID));
                        if (parties.confirm(
                                        party,
                                        Map.of(100L, Position.TOP, partner, Position.MID),
                                        System.currentTimeMillis())
                                .isConfirmed()) {
                            confirmed.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(confirmed.get()).isEqualTo(1);
        assertThat(redis.opsForSet().size(RedisKeys.PARTY_INDEX)).isEqualTo(1);
    }
}

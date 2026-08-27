package com.port051.queuemate.matching.loop;

import com.port051.queuemate.matching.domain.GameQueue;
import com.port051.queuemate.matching.domain.MatchRequest;
import com.port051.queuemate.matching.domain.Partition;
import com.port051.queuemate.matching.domain.Party;
import com.port051.queuemate.matching.domain.Position;
import com.port051.queuemate.matching.domain.Purpose;
import com.port051.queuemate.matching.domain.VoiceMode;
import com.port051.queuemate.matching.store.ClaimStore;
import com.port051.queuemate.matching.store.RedisClock;
import com.port051.queuemate.matching.store.RequestExpiry;
import com.port051.queuemate.matching.store.RequestStore;
import com.port051.queuemate.matching.store.WaitingList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 05-realtime-matching-contract 3장 — 부품이 실제로 엮이는지 확인한다.
 *
 * <p>배경 스케줄러를 끈다. 켜져 있으면 테스트가 준비한 명단을 확인하기 전에 비워 버린다.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "queuemate.matching.scheduled=false")
class MatchingTickTest {

    @Container
    @ServiceConnection
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static final Partition DUO = new Partition(GameQueue.SOLO_DUO, 2);
    private static final Partition FLEX_FIVE = new Partition(GameQueue.FLEX, 5);

    @Autowired
    MatchingTick tick;

    @Autowired
    WaitingList waitingList;

    @Autowired
    RequestStore requestStore;

    @Autowired
    ClaimStore claims;

    @Autowired
    RequestExpiry expiry;

    @Autowired
    RedisClock clock;

    @Autowired
    StringRedisTemplate strings;

    @BeforeEach
    void clear() {
        strings.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    private MatchRequest request(long requestId, GameQueue queue, int targetSize, Position primary) {
        return request(requestId, queue, targetSize, primary, clock.nowMillis() + requestId);
    }

    private static MatchRequest request(long requestId, GameQueue queue, int targetSize,
                                        Position primary, long requestedAt) {
        return new MatchRequest(
                requestId, requestId,
                queue, targetSize, Purpose.RANK_UP, 120, VoiceMode.POSSIBLE,
                primary, List.of(Position.TOP, Position.JUNGLE, Position.MID, Position.BOTTOM, Position.SUPPORT),
                14, 1, 30,
                requestedAt);
    }

    private void register(MatchRequest request) {
        requestStore.save(request);
        waitingList.add(request);
    }

    private static List<Long> requestIdsOf(Party party) {
        return party.members().stream().map(MatchRequest::requestId).toList();
    }

    @Test
    @DisplayName("조건이 맞는 둘이 대기 중이면 파티가 성립한다")
    void formsAPartyFromTheWaitingList() {
        register(request(1L, GameQueue.SOLO_DUO, 2, Position.MID));
        register(request(2L, GameQueue.SOLO_DUO, 2, Position.TOP));

        List<Party> confirmed = tick.runOnce();

        assertThat(confirmed).hasSize(1);
        assertThat(requestIdsOf(confirmed.getFirst())).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("성립한 파티는 명단과 메모에서 사라진다")
    void confirmedPartiesLeaveTheWaitingList() {
        register(request(1L, GameQueue.SOLO_DUO, 2, Position.MID));
        register(request(2L, GameQueue.SOLO_DUO, 2, Position.TOP));

        tick.runOnce();

        assertThat(waitingList.requestIds(DUO)).isEmpty();
        assertThat(requestStore.find(1L)).isEmpty();
        assertThat(requestStore.find(2L)).isEmpty();
    }

    @Test
    @DisplayName("확정한 뒤 배정 중 표시를 떼고 나간다")
    void releasesClaimsAfterConfirming() {
        register(request(1L, GameQueue.SOLO_DUO, 2, Position.MID));
        register(request(2L, GameQueue.SOLO_DUO, 2, Position.TOP));

        tick.runOnce();

        assertThat(claims.isClaimed(1L)).isFalse();
        assertThat(claims.isClaimed(2L)).isFalse();
    }

    @Test
    @DisplayName("목표 인원을 못 채우면 아무도 건드리지 않는다")
    void leavesEveryoneWaitingWhenNobodyCanBeMatched() {
        register(request(1L, GameQueue.FLEX, 5, Position.MID));
        register(request(2L, GameQueue.FLEX, 5, Position.TOP));

        assertThat(tick.runOnce()).isEmpty();
        assertThat(waitingList.requestIds(FLEX_FIVE)).containsExactly(1L, 2L);
        assertThat(requestStore.find(1L)).isPresent();
    }

    @Test
    @DisplayName("대기자가 없으면 아무 일도 없다")
    void doesNothingWhenNobodyIsWaiting() {
        assertThat(tick.runOnce()).isEmpty();
    }

    @Test
    @DisplayName("한 바퀴에 여러 파티가 성립한다")
    void confirmsSeveralPartiesInOneRound() {
        for (long id = 1; id <= 4; id++) {
            register(request(id, GameQueue.SOLO_DUO, 2, Position.values()[(int) id % 5]));
        }

        assertThat(tick.runOnce()).hasSize(2);
        assertThat(waitingList.requestIds(DUO)).isEmpty();
    }

    @Test
    @DisplayName("짝이 없는 사람은 다음 바퀴를 위해 남는다")
    void leftoverRequestsStayForTheNextRound() {
        register(request(1L, GameQueue.SOLO_DUO, 2, Position.MID));
        register(request(2L, GameQueue.SOLO_DUO, 2, Position.TOP));
        register(request(3L, GameQueue.SOLO_DUO, 2, Position.JUNGLE));

        tick.runOnce();

        assertThat(waitingList.requestIds(DUO)).containsExactly(3L);
    }

    @Nested
    @DisplayName("조합")
    class Partitions {

        @Test
        @DisplayName("큐와 목표 인원이 다른 요청은 서로 다른 명단에 선다")
        void differentCombinationsLiveInDifferentLists() {
            register(request(1L, GameQueue.SOLO_DUO, 2, Position.MID));
            register(request(2L, GameQueue.FLEX, 5, Position.TOP));

            assertThat(waitingList.requestIds(DUO)).containsExactly(1L);
            assertThat(waitingList.requestIds(FLEX_FIVE)).containsExactly(2L);
        }

        @Test
        @DisplayName("한 바퀴가 모든 조합을 훑는다")
        void oneRoundVisitsEveryCombination() {
            register(request(1L, GameQueue.SOLO_DUO, 2, Position.MID));
            register(request(2L, GameQueue.SOLO_DUO, 2, Position.TOP));
            register(request(3L, GameQueue.NORMAL, 2, Position.MID));
            register(request(4L, GameQueue.NORMAL, 2, Position.TOP));

            assertThat(tick.runOnce()).hasSize(2);
        }

        @Test
        @DisplayName("한 조합만 돌릴 수도 있다")
        void canRunASingleCombination() {
            register(request(1L, GameQueue.SOLO_DUO, 2, Position.MID));
            register(request(2L, GameQueue.SOLO_DUO, 2, Position.TOP));
            register(request(3L, GameQueue.NORMAL, 2, Position.MID));
            register(request(4L, GameQueue.NORMAL, 2, Position.TOP));

            assertThat(tick.runOnce(DUO)).hasSize(1);
            assertThat(waitingList.requestIds(new Partition(GameQueue.NORMAL, 2))).containsExactly(3L, 4L);
        }
    }

    @Nested
    @DisplayName("만료")
    class Expiry {

        @Test
        @DisplayName("만료된 요청은 매칭 전에 정리된다")
        void expiredRequestsAreSweptBeforeMatching() {
            long tooOld = clock.nowMillis() - expiry.maxWait().toMillis() - 1_000;
            register(request(1L, GameQueue.SOLO_DUO, 2, Position.MID, tooOld));
            register(request(2L, GameQueue.SOLO_DUO, 2, Position.TOP));

            assertThat(tick.runOnce()).isEmpty();
            assertThat(waitingList.requestIds(DUO)).containsExactly(2L);
        }

        @Test
        @DisplayName("만료된 사람이 파티에 들어가지 않는다")
        void expiredRequestsNeverJoinAParty() {
            long tooOld = clock.nowMillis() - expiry.maxWait().toMillis() - 1_000;
            register(request(1L, GameQueue.SOLO_DUO, 2, Position.MID, tooOld));
            register(request(2L, GameQueue.SOLO_DUO, 2, Position.TOP, tooOld));
            register(request(3L, GameQueue.SOLO_DUO, 2, Position.JUNGLE));
            register(request(4L, GameQueue.SOLO_DUO, 2, Position.BOTTOM));

            List<Party> confirmed = tick.runOnce();

            assertThat(confirmed).hasSize(1);
            assertThat(requestIdsOf(confirmed.getFirst())).containsExactly(3L, 4L);
        }
    }

    @Test
    @DisplayName("남이 먼저 잡고 있으면 그 파티는 건너뛴다")
    void skipsPartiesAlreadyClaimedBySomeoneElse() {
        register(request(1L, GameQueue.SOLO_DUO, 2, Position.MID));
        register(request(2L, GameQueue.SOLO_DUO, 2, Position.TOP));
        claims.claimAll(List.of(1L));

        assertThat(tick.runOnce()).isEmpty();
        // 못 잡았으니 명단은 그대로다. 다음 바퀴에 다시 시도한다.
        assertThat(waitingList.requestIds(DUO)).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("두 번 돌려도 같은 요청이 두 파티에 들어가지 않는다")
    void neverConfirmsTheSameRequestTwice() {
        register(request(1L, GameQueue.SOLO_DUO, 2, Position.MID));
        register(request(2L, GameQueue.SOLO_DUO, 2, Position.TOP));

        assertThat(tick.runOnce()).hasSize(1);
        assertThat(tick.runOnce()).isEmpty();
    }
}

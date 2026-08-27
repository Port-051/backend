package com.port051.queuemate.matching;

import com.port051.queuemate.matching.domain.GameQueue;
import com.port051.queuemate.matching.domain.MatchRequest;
import com.port051.queuemate.matching.domain.Party;
import com.port051.queuemate.matching.domain.PartyMatcher;
import com.port051.queuemate.matching.domain.Position;
import com.port051.queuemate.matching.domain.Purpose;
import com.port051.queuemate.matching.domain.VoiceMode;
import com.port051.queuemate.matching.store.ClaimStore;
import com.port051.queuemate.matching.store.RequestStore;
import com.port051.queuemate.matching.store.WaitingList;
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
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * claim이 없으면 무엇이 깨지는지. 05-realtime-matching-contract 3장.
 *
 * <p>매칭 루프는 <b>명단을 읽고 → 파티를 만들고 → 확정한다</b>인데, 읽기와 확정 사이에
 * 다른 인스턴스가 같은 명단을 읽어 갈 수 있다. {@link PartyMatcher}는 결정적이라 같은 입력에
 * 같은 파티를 내놓으므로, 두 인스턴스가 같은 순간에 읽으면 <b>같은 파티를 만들어 둘 다 확정한다.</b>
 * 한 요청은 최대 하나의 파티에만 들어간다는 INV-3이 깨진다.
 *
 * <p>그 틈은 실제로는 밀리초 단위라 그냥 돌려서는 좀처럼 잡히지 않는다. 그래서 여기서는
 * barrier로 <b>두 인스턴스가 같은 명단을 손에 쥔 순간</b>을 만들어 재현한다. 재현되지 않는다고
 * 없는 것이 아니라 드물게 나는 것이며, 매칭 주기가 짧을수록 자주 난다.
 *
 * <p>뒤의 테스트는 계약 3장의 claim 한 단계를 넣으면 같은 상황에서 그것이 사라짐을 보인다.
 */
@SpringBootTest
@Testcontainers
class MatchingRaceTest {

    @Container
    @ServiceConnection
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Autowired
    WaitingList waiting;

    @Autowired
    RequestStore requests;

    @Autowired
    ClaimStore claims;

    @Autowired
    StringRedisTemplate strings;

    /** 둘이 만나면 바로 파티가 되는 요청 둘. 조건은 전부 서로 맞다. */
    @BeforeEach
    void twoRequestsWaiting() {
        strings.getConnectionFactory().getConnection().serverCommands().flushAll();
        for (long requestId = 1; requestId <= 2; requestId++) {
            MatchRequest request = request(requestId);
            requests.save(request);
            waiting.add(request);
        }
    }

    @Test
    @DisplayName("claim이 없으면 같은 두 사람이 두 파티에 들어간다")
    void withoutClaimTheSameRequestsLandInTwoParties() throws Exception {
        List<List<Party>> confirmed = raceTwoInstances(false);

        assertThat(confirmed.get(0)).as("한쪽이 확정한 파티").hasSize(1);
        assertThat(confirmed.get(1)).as("다른 쪽이 확정한 파티").hasSize(1);
        assertThat(memberIdsOf(confirmed.get(0).getFirst()))
                .as("같은 요청으로 만든 서로 다른 파티다 — INV-3 위반")
                .isEqualTo(memberIdsOf(confirmed.get(1).getFirst()));

        // 명단만 보면 멀쩡하다. 같은 것을 두 번 지웠을 뿐이라 흔적이 남지 않는다.
        // 이 문제를 명단에서 찾을 수 없는 이유다.
        assertThat(waiting.size()).isZero();
    }

    @Test
    @DisplayName("claim을 걸면 한 인스턴스만 확정한다")
    void claimLetsOnlyOneInstanceConfirm() throws Exception {
        List<List<Party>> confirmed = raceTwoInstances(true);

        assertThat(confirmed.get(0).size() + confirmed.get(1).size())
                .as("확정된 파티 수")
                .isEqualTo(1);
        assertThat(waiting.size()).isZero();
    }

    /** 두 인스턴스가 같은 순간에 명단을 읽도록 맞춘 뒤 각자 한 바퀴를 돈다. */
    private List<List<Party>> raceTwoInstances(boolean withClaim) throws Exception {
        CyclicBarrier read = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Future<List<Party>> left = pool.submit(() -> cycle(read, withClaim));
            Future<List<Party>> right = pool.submit(() -> cycle(read, withClaim));
            return List.of(left.get(), right.get());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 매칭 루프 한 바퀴. 계약 3장 그대로이고, {@code withClaim}이 거짓이면 3단계만 뺀다.
     *
     * <p>확정한 claim은 떼지 않는다. 요청이 이미 명단에서 빠졌으므로 뗄 이유가 없기도 하지만,
     * 곧바로 떼면 낡은 명단을 든 인스턴스가 그 자리를 다시 잡아 같은 문제가 돌아온다.
     * TTL이 지나 풀릴 즈음이면 그 명단은 어차피 남아 있지 않다.
     */
    private List<Party> cycle(CyclicBarrier read, boolean withClaim) throws Exception {
        // 1·2단계 — 명단을 읽고 메모를 본다.
        List<MatchRequest> snapshot = waiting.requestIds().stream()
                .map(requests::find)
                .flatMap(Optional::stream)
                .toList();
        read.await();

        List<Party> confirmed = new ArrayList<>();
        for (Party party : PartyMatcher.match(snapshot)) {
            List<Long> memberIds = memberIdsOf(party);
            if (withClaim && claims.claimAll(memberIds).isEmpty()) {
                // 3단계 — 누가 먼저 잡았다. 이번 사이클은 넘긴다.
                continue;
            }
            // 4단계 — 확정. 명단에서 뺀다.
            memberIds.forEach(waiting::remove);
            confirmed.add(party);
        }
        return confirmed;
    }

    private static List<Long> memberIdsOf(Party party) {
        return party.members().stream().map(MatchRequest::requestId).toList();
    }

    private static MatchRequest request(long requestId) {
        return new MatchRequest(
                requestId, requestId,
                GameQueue.SOLO_DUO, 2, Purpose.RANK_UP, 60, VoiceMode.POSSIBLE,
                Position.MID, List.of(Position.TOP, Position.JUNGLE, Position.BOTTOM, Position.SUPPORT),
                14, 1, 30,
                requestId);
    }
}

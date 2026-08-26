package com.port051.queuemate.matching.store;

import com.port051.queuemate.matching.domain.GameQueue;
import com.port051.queuemate.matching.domain.MatchRequest;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 대기 명단이 4.3 1단계의 전순서를 지키는지 확인한다. */
@SpringBootTest
@Testcontainers
class WaitingListTest {

    @Container
    @ServiceConnection
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Autowired
    WaitingList waitingList;

    @Autowired
    StringRedisTemplate strings;

    @BeforeEach
    void clear() {
        strings.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    private static MatchRequest request(long requestId, long requestedAt) {
        return new MatchRequest(
                requestId, requestId,
                GameQueue.SOLO_DUO, 2, Purpose.RANK_UP, 120, VoiceMode.POSSIBLE,
                Position.MID, List.of(),
                14, 11, 18,
                requestedAt);
    }

    @Test
    @DisplayName("신청이 빠른 순으로 읽힌다")
    void readsInRequestedOrder() {
        waitingList.add(request(1L, 300));
        waitingList.add(request(2L, 100));
        waitingList.add(request(3L, 200));

        assertThat(waitingList.requestIds()).containsExactly(2L, 3L, 1L);
    }

    @Test
    @DisplayName("넣은 순서와 무관하게 신청 시각 순이다")
    void insertionOrderDoesNotMatter() {
        waitingList.add(request(3L, 200));
        waitingList.add(request(1L, 300));
        waitingList.add(request(2L, 100));

        assertThat(waitingList.requestIds()).containsExactly(2L, 3L, 1L);
    }

    @Test
    @DisplayName("신청 시각이 같으면 요청 ID가 작은 쪽이 먼저다")
    void breaksTiesByRequestId() {
        waitingList.add(request(10L, 100));
        waitingList.add(request(9L, 100));

        assertThat(waitingList.requestIds()).containsExactly(9L, 10L);
    }

    @Test
    @DisplayName("자리수가 다른 요청 ID도 숫자 크기 순이다")
    void ordersByNumericValueNotByText() {
        waitingList.add(request(100L, 100));
        waitingList.add(request(9L, 100));
        waitingList.add(request(1L, 100));
        waitingList.add(request(10L, 100));

        assertThat(waitingList.requestIds()).containsExactly(1L, 9L, 10L, 100L);
    }

    @Test
    @DisplayName("아주 큰 요청 ID도 순서가 맞다")
    void ordersHugeRequestIdsCorrectly() {
        waitingList.add(request(Long.MAX_VALUE, 100));
        waitingList.add(request(999_999_999_999_999_999L, 100));
        waitingList.add(request(1L, 100));

        assertThat(waitingList.requestIds())
                .containsExactly(1L, 999_999_999_999_999_999L, Long.MAX_VALUE);
    }

    @Test
    @DisplayName("멤버는 자리수를 채워 저장한다")
    void storesZeroPaddedMembers() {
        waitingList.add(request(9L, 100));

        // 자리수를 채우지 않으면 같은 시각의 동률이 사전순으로 갈려 순서가 뒤집힌다.
        assertThat(strings.opsForZSet().range("mq:instant", 0, -1))
                .containsExactly("0000000000000000009");
    }

    @Test
    @DisplayName("지운 요청은 명단에 없다")
    void removedRequestsDisappear() {
        waitingList.add(request(1L, 100));
        waitingList.add(request(2L, 200));

        waitingList.remove(1L);

        assertThat(waitingList.requestIds()).containsExactly(2L);
    }

    @Test
    @DisplayName("없는 요청을 지워도 문제되지 않는다")
    void removingAMissingRequestIsFine() {
        waitingList.remove(99999L);

        assertThat(waitingList.requestIds()).isEmpty();
    }

    @Test
    @DisplayName("대기 인원을 센다")
    void countsWaitingRequests() {
        assertThat(waitingList.size()).isZero();

        waitingList.add(request(1L, 100));
        waitingList.add(request(2L, 200));

        assertThat(waitingList.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 요청을 두 번 넣어도 하나다")
    void addingTwiceKeepsOneEntry() {
        waitingList.add(request(1L, 100));
        waitingList.add(request(1L, 100));

        assertThat(waitingList.size()).isEqualTo(1);
    }
}

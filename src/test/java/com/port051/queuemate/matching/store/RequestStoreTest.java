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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 진짜 Redis에 {@code req:}를 넣고 다시 읽는다.
 *
 * <p>단위 테스트로 대체할 수 없다. 확인하려는 것이 자바 객체가 아니라
 * <b>Redis 안에 실제로 들어간 바이트</b>이기 때문이다.
 */
@SpringBootTest
@Testcontainers
class RequestStoreTest {

    @Container
    @ServiceConnection
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    RequestStore store;

    @Autowired
    StringRedisTemplate strings;

    @BeforeEach
    void clear() {
        strings.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    private static MatchRequest request() {
        return new MatchRequest(
                10482L, 7L,
                GameQueue.SOLO_DUO, 5, Purpose.RANK_UP, 120, VoiceMode.POSSIBLE,
                Position.MID, List.of(Position.TOP, Position.JUNGLE),
                14, 11, 18,
                1_755_960_000_000L);
    }

    @Test
    @DisplayName("저장한 요청을 그대로 다시 읽는다")
    void savesAndReads() {
        store.save(request());

        assertThat(store.find(10482L)).contains(request());
    }

    @Test
    @DisplayName("계약이 정한 키에 저장한다")
    void usesTheContractKey() {
        store.save(request());

        assertThat(strings.hasKey("req:10482")).isTrue();
    }

    @Test
    @DisplayName("저장된 값은 계약이 정한 필드 열셋짜리 JSON이다")
    void storesTheContractJson() {
        store.save(request());

        JsonNode json = JSON.readTree(strings.opsForValue().get("req:10482"));

        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
                "requestId", "userId",
                "queue", "targetSize", "purpose", "playMinutes", "voiceMode",
                "primaryPosition", "subPositions",
                "tierOrder", "allowedTierMinOrder", "allowedTierMaxOrder",
                "requestedAt");
    }

    @Test
    @DisplayName("자바 전용 표현으로 저장하지 않는다")
    void neverStoresAJavaOnlyPayload() {
        store.save(request());

        String raw = strings.opsForValue().get("req:10482");

        // JDK 직렬화라면 매직 바이트로 시작하고 자바 클래스 이름이 박힌다.
        assertThat(raw).startsWith("{").doesNotContain("com.port051");
    }

    @Test
    @DisplayName("없는 요청을 읽으면 빈 값이다")
    void readingAMissingRequestGivesNothing() {
        assertThat(store.find(99999L)).isEmpty();
    }

    @Test
    @DisplayName("지운 요청은 읽히지 않는다")
    void deletedRequestsDisappear() {
        store.save(request());

        store.delete(10482L);

        assertThat(store.find(10482L)).isEmpty();
    }

    @Test
    @DisplayName("없는 요청을 지워도 문제되지 않는다")
    void deletingAMissingRequestIsFine() {
        store.delete(99999L);

        assertThat(store.find(99999L)).isEmpty();
    }

    @Test
    @DisplayName("같은 요청 ID로 저장하면 덮어쓴다")
    void savingTwiceOverwrites() {
        store.save(request());
        MatchRequest changed = new MatchRequest(
                10482L, 7L,
                GameQueue.NORMAL, 2, Purpose.CASUAL, 60, VoiceMode.NOT_USED,
                Position.TOP, List.of(),
                20, 18, 22,
                1_755_960_000_001L);

        store.save(changed);

        assertThat(store.find(10482L)).contains(changed);
    }
}

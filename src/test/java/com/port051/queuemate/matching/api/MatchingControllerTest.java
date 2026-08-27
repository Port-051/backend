package com.port051.queuemate.matching.api;

import com.port051.queuemate.matching.domain.GameQueue;
import com.port051.queuemate.matching.domain.Partition;
import com.port051.queuemate.matching.domain.Position;
import com.port051.queuemate.matching.domain.Purpose;
import com.port051.queuemate.matching.domain.VoiceMode;
import com.port051.queuemate.matching.store.RequestStore;
import com.port051.queuemate.matching.store.UserGuard;
import com.port051.queuemate.matching.store.WaitingList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 05-realtime-matching-contract "이번 단계의 범위" — 요청을 받아 Redis에 넣는다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class MatchingControllerTest {

    @Container
    @ServiceConnection
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static final Partition DUO = new Partition(GameQueue.SOLO_DUO, 2);

    @LocalServerPort
    int port;

    private RestClient http;

    @Autowired
    WaitingList waitingList;

    @Autowired
    RequestStore requestStore;

    @Autowired
    UserGuard userGuard;

    @Autowired
    StringRedisTemplate strings;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeEach
    void clear() {
        strings.getConnectionFactory().getConnection().serverCommands().flushAll();
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                // 4xx·5xx에 예외를 던지지 않게 한다. 상태 코드 자체가 검사 대상이다.
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
                .build();
    }

    private ResponseEntity<String> post(String json) {
        return http.post().uri("/api/match-requests")
                .contentType(MediaType.APPLICATION_JSON).body(json)
                .retrieve().toEntity(String.class);
    }

    private ResponseEntity<String> delete(long requestId) {
        return http.delete().uri("/api/match-requests/{id}", requestId)
                .retrieve().toEntity(String.class);
    }

    private ResponseEntity<String> waitingCount(GameQueue queue, int targetSize) {
        return http.get()
                .uri(uri -> uri.path("/api/match-requests/waiting")
                        .queryParam("queue", queue).queryParam("targetSize", targetSize).build())
                .retrieve().toEntity(String.class);
    }

    private static JsonNode json(ResponseEntity<String> response) {
        return JSON.readTree(response.getBody());
    }

    private static String body(long userId) {
        return body(userId, GameQueue.SOLO_DUO, 2);
    }

    private static String body(long userId, GameQueue queue, int targetSize) {
        return """
                {
                  "userId": %d,
                  "queue": "%s",
                  "targetSize": %d,
                  "purpose": "RANK_UP",
                  "playMinutes": 120,
                  "voiceMode": "POSSIBLE",
                  "primaryPosition": "MID",
                  "subPositions": ["TOP"],
                  "tierOrder": 14,
                  "allowedTierMinOrder": 11,
                  "allowedTierMaxOrder": 18
                }
                """.formatted(userId, queue, targetSize);
    }

    private long register(String json) {
        ResponseEntity<String> response = post(json);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return json(response).get("requestId").asLong();
    }

    @Test
    @DisplayName("신청하면 계약이 정한 모양으로 돌려준다")
    void registeringAnswersWithTheContractShape() {
        ResponseEntity<String> response = post(body(7L));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        JsonNode created = json(response);
        assertThat(created.get("requestId").asLong()).isPositive();
        assertThat(created.get("userId").asLong()).isEqualTo(7L);
        assertThat(created.get("queue").asString()).isEqualTo("SOLO_DUO");
        assertThat(created.get("requestedAt").asLong()).isPositive();
        assertThat(created.get("subPositions").get(0).asString()).isEqualTo("TOP");
        // 계약 2장이 고정한 필드 열셋이 그대로 나가야 한다.
        assertThat(created.propertyNames()).hasSize(13);
    }

    @Test
    @DisplayName("신청한 요청이 명단과 메모에 들어간다")
    void registeringPutsTheRequestIntoRedis() {
        long requestId = register(body(7L));

        assertThat(waitingList.requestIds(DUO)).containsExactly(requestId);
        assertThat(requestStore.find(requestId)).isPresent();
    }

    @Test
    @DisplayName("요청 ID는 서버가 발급하고 겹치지 않는다")
    void requestIdsAreIssuedByTheServer() {
        long first = register(body(1L));
        long second = register(body(2L));

        assertThat(second).isGreaterThan(first);
    }

    @Test
    @DisplayName("신청 시각도 서버가 정한다")
    void requestedAtComesFromTheServer() {
        long before = System.currentTimeMillis();

        long requestedAt = json(post(body(7L))).get("requestedAt").asLong();

        assertThat(requestedAt).isBetween(before - 60_000, System.currentTimeMillis() + 60_000);
    }

    @Nested
    @DisplayName("사용자당 하나")
    class OnePerUser {

        @Test
        @DisplayName("같은 사용자가 두 번 신청하면 막는다")
        void rejectsASecondRequestFromTheSameUser() {
            long first = register(body(7L));

            ResponseEntity<String> second = post(body(7L));

            assertThat(second.getStatusCode().value()).isEqualTo(409);
            assertThat(json(second).get("requestId").asLong()).isEqualTo(first);
        }

        @Test
        @DisplayName("조건이 달라도 같은 사용자면 막는다")
        void rejectsEvenWhenTheConditionsDiffer() {
            register(body(7L, GameQueue.SOLO_DUO, 2));

            assertThat(post(body(7L, GameQueue.FLEX, 5)).getStatusCode().value()).isEqualTo(409);
        }

        @Test
        @DisplayName("다른 사용자는 막지 않는다")
        void lettingOtherUsersThrough() {
            register(body(7L));

            assertThat(post(body(8L)).getStatusCode().value()).isEqualTo(201);
        }

        @Test
        @DisplayName("취소하면 다시 신청할 수 있다")
        void cancellingFreesTheSlot() {
            long requestId = register(body(7L));

            assertThat(delete(requestId).getStatusCode().value()).isEqualTo(204);

            assertThat(post(body(7L)).getStatusCode().value()).isEqualTo(201);
        }
    }

    @Nested
    @DisplayName("취소")
    class Cancel {

        @Test
        @DisplayName("취소하면 명단과 메모에서 사라진다")
        void cancelledRequestsDisappear() {
            long requestId = register(body(7L));

            delete(requestId);

            assertThat(waitingList.requestIds(DUO)).isEmpty();
            assertThat(requestStore.find(requestId)).isEmpty();
            assertThat(userGuard.waitingRequestId(7L)).isEmpty();
        }

        @Test
        @DisplayName("없는 요청을 취소하면 찾을 수 없다고 답한다")
        void cancellingAMissingRequestIsNotFound() {
            assertThat(delete(99999L).getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("두 번 취소하면 두 번째는 찾을 수 없다")
        void cancellingTwiceFailsTheSecondTime() {
            long requestId = register(body(7L));

            assertThat(delete(requestId).getStatusCode().value()).isEqualTo(204);
            assertThat(delete(requestId).getStatusCode().value()).isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("잘못된 신청")
    class Invalid {

        @Test
        @DisplayName("게임 규칙에 없는 인원은 거절한다")
        void rejectsImpossiblePartySizes() {
            // 솔로·듀오 랭크는 2인만 가능하다(3.4).
            assertThat(post(body(7L, GameQueue.SOLO_DUO, 5)).getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("자유 랭크의 4인 파티를 거절한다")
        void rejectsFourPlayerFlex() {
            assertThat(post(body(7L, GameQueue.FLEX, 4)).getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("주 포지션이 없으면 거절한다")
        void rejectsAMissingPrimaryPosition() {
            String json = body(7L).replace("\"primaryPosition\": \"MID\",", "");

            assertThat(post(json).getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("본인 티어를 담지 않는 허용 범위를 거절한다")
        void rejectsARangeThatExcludesTheRequester() {
            String json = body(7L).replace("\"tierOrder\": 14", "\"tierOrder\": 25");

            assertThat(post(json).getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("거절된 신청은 자리를 잡아두지 않는다")
        void rejectedRequestsDoNotHoldTheSlot() {
            assertThat(post(body(7L, GameQueue.SOLO_DUO, 5)).getStatusCode().value()).isEqualTo(400);

            assertThat(post(body(7L)).getStatusCode().value()).isEqualTo(201);
        }
    }

    @Test
    @DisplayName("조합의 대기 인원을 알려준다")
    void reportsHowManyAreWaiting() {
        register(body(1L));
        register(body(2L));
        register(body(3L, GameQueue.FLEX, 5));

        ResponseEntity<String> response = waitingCount(GameQueue.SOLO_DUO, 2);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(json(response).get("waiting").asLong()).isEqualTo(2);
    }
}

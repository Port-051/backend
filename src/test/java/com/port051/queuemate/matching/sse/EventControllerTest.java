package com.port051.queuemate.matching.sse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제로 SSE 연결을 열고 소식을 받는지 본다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class EventControllerTest {

    @Container
    @ServiceConnection
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @LocalServerPort
    int port;

    @Autowired
    EmitterRegistry emitters;

    @Autowired
    EventPublisher publisher;

    @Autowired
    StringRedisTemplate strings;

    @BeforeEach
    void clear() {
        strings.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    /** SSE는 응답이 끝나지 않으므로 스트림을 직접 읽는다. */
    private record Stream(HttpURLConnection connection, List<String> lines, CountDownLatch received) {

        void close() {
            connection.disconnect();
        }
    }

    private Stream openStream(long userId, int expectedLines) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI
                .create("http://localhost:" + port + "/api/users/" + userId + "/events")
                .toURL().openConnection();
        connection.setRequestProperty("Accept", MediaType.TEXT_EVENT_STREAM_VALUE);
        connection.connect();

        List<String> lines = new ArrayList<>();
        CountDownLatch received = new CountDownLatch(expectedLines);
        Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        lines.add(line);
                        received.countDown();
                    }
                }
            } catch (Exception ignored) {
                // 연결을 닫으면 여기로 온다.
            }
        });
        return new Stream(connection, lines, received);
    }

    @Test
    @DisplayName("연결하면 등록된다")
    void connectingRegistersTheEmitter() throws Exception {
        Stream stream = openStream(71L, 1);

        try {
            assertThat(waitFor(() -> emitters.connectionCount(71L) == 1)).isTrue();
        } finally {
            stream.close();
        }
    }

    @Test
    @DisplayName("뿌려진 소식이 연결로 나간다")
    void publishedEventsReachTheStream() throws Exception {
        Stream stream = openStream(72L, 2);
        assertThat(waitFor(() -> emitters.connectionCount(72L) == 1)).isTrue();

        try {
            publisher.publish(new MatchingEvent(
                    MatchingEvent.Type.PARTY_CONFIRMED, 72L, 10482L,
                    com.port051.queuemate.matching.domain.GameQueue.SOLO_DUO, 2,
                    com.port051.queuemate.matching.domain.Purpose.RANK_UP,
                    com.port051.queuemate.matching.domain.Position.MID, false));

            assertThat(stream.received().await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(stream.lines()).anyMatch(line -> line.equals("event:PARTY_CONFIRMED"));
            assertThat(stream.lines()).anyMatch(line -> line.contains("\"requestId\":10482"));
        } finally {
            stream.close();
        }
    }

    @Test
    @DisplayName("남의 소식은 나가지 않는다")
    void neverSendsSomeoneElsesEvent() throws Exception {
        Stream stream = openStream(73L, 1);
        assertThat(waitFor(() -> emitters.connectionCount(73L) == 1)).isTrue();

        try {
            publisher.publish(new MatchingEvent(
                    MatchingEvent.Type.PARTY_CONFIRMED, 999L, 999L,
                    com.port051.queuemate.matching.domain.GameQueue.SOLO_DUO, 2,
                    com.port051.queuemate.matching.domain.Purpose.RANK_UP,
                    com.port051.queuemate.matching.domain.Position.MID, false));

            assertThat(stream.received().await(1, TimeUnit.SECONDS)).isFalse();
            assertThat(stream.lines()).isEmpty();
        } finally {
            stream.close();
        }
    }

    @Test
    @DisplayName("한 사용자가 여러 연결을 열 수 있다")
    void oneUserCanOpenSeveralStreams() throws Exception {
        Stream first = openStream(74L, 1);
        Stream second = openStream(74L, 1);

        try {
            assertThat(waitFor(() -> emitters.connectionCount(74L) == 2)).isTrue();
        } finally {
            first.close();
            second.close();
        }
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}

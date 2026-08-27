package com.port051.queuemate.matching.sse;

import com.port051.queuemate.matching.domain.GameQueue;
import com.port051.queuemate.matching.domain.MatchRequest;
import com.port051.queuemate.matching.domain.Position;
import com.port051.queuemate.matching.domain.Purpose;
import com.port051.queuemate.matching.domain.VoiceMode;
import com.port051.queuemate.matching.loop.MatchingTick;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 05-realtime-matching-contract 3장 4단계 — 확정을 알린다.
 *
 * <p>Pub/Sub 자체를 확인한다. 실제 채널에 발행하고, 구독한 쪽이 받는지 본다.
 * SSE 연결까지 태우지 않는 이유는 이 구간이 <b>인스턴스를 건너뛰는 유일한 지점</b>이라
 * 여기만 맞으면 나머지는 인스턴스 안의 일이기 때문이다.
 */
@SpringBootTest
@Testcontainers
class MatchingEventFlowTest {

    @Container
    @ServiceConnection
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Autowired
    MatchingTick tick;

    @Autowired
    WaitingList waitingList;

    @Autowired
    RequestStore requestStore;

    @Autowired
    RequestExpiry expiry;

    @Autowired
    RedisClock clock;

    @Autowired
    EventPublisher publisher;

    @Autowired
    StringRedisTemplate strings;

    /** 다른 인스턴스가 구독하고 있는 상황을 흉내낸다. */
    private final List<MatchingEvent> received = new CopyOnWriteArrayList<>();
    private org.springframework.data.redis.listener.RedisMessageListenerContainer container;
    private CountDownLatch arrived;

    @BeforeEach
    void clear() throws Exception {
        strings.getConnectionFactory().getConnection().serverCommands().flushAll();
        received.clear();
    }

    private void subscribe(int expectedEvents) throws Exception {
        arrived = new CountDownLatch(expectedEvents);
        EmitterRegistry registry = new EmitterRegistry() {
            @Override
            public int send(MatchingEvent event) {
                received.add(event);
                arrived.countDown();
                return 1;
            }
        };
        container = new org.springframework.data.redis.listener.RedisMessageListenerContainer();
        container.setConnectionFactory(strings.getConnectionFactory());
        container.addMessageListener(new EventSubscriber(registry),
                new org.springframework.data.redis.listener.ChannelTopic(EventPublisher.CHANNEL));
        container.afterPropertiesSet();
        container.start();
        // 구독이 실제로 붙기를 기다린다. 붙기 전에 발행하면 그 소식은 사라진다.
        Thread.sleep(300);
    }

    private void awaitEvents() throws Exception {
        assertThat(arrived.await(5, TimeUnit.SECONDS)).as("소식이 도착해야 한다").isTrue();
        container.stop();
    }

    private MatchRequest request(long requestId, Position primary, long requestedAt) {
        return new MatchRequest(
                requestId, requestId,
                GameQueue.SOLO_DUO, 2, Purpose.RANK_UP, 120, VoiceMode.POSSIBLE,
                primary, List.of(Position.TOP, Position.JUNGLE, Position.MID),
                14, 1, 30,
                requestedAt);
    }

    private void register(MatchRequest request) {
        requestStore.save(request);
        waitingList.add(request);
    }

    @Nested
    @DisplayName("파티 확정")
    class Confirmed {

        @Test
        @DisplayName("참가자 수만큼 소식이 간다")
        void everyMemberHearsAboutIt() throws Exception {
            subscribe(2);
            register(request(1L, Position.MID, clock.nowMillis()));
            register(request(2L, Position.TOP, clock.nowMillis() + 1));

            tick.runOnce();

            awaitEvents();
            assertThat(received).hasSize(2);
            assertThat(received).allMatch(e -> e.type() == MatchingEvent.Type.PARTY_CONFIRMED);
            assertThat(received.stream().map(MatchingEvent::userId)).containsExactlyInAnyOrder(1L, 2L);
        }

        @Test
        @DisplayName("각자 자기 배정 포지션을 받는다")
        void everyMemberHearsTheirOwnPosition() throws Exception {
            subscribe(2);
            register(request(1L, Position.MID, clock.nowMillis()));
            register(request(2L, Position.TOP, clock.nowMillis() + 1));

            tick.runOnce();

            awaitEvents();
            assertThat(received.stream().map(MatchingEvent::position))
                    .containsExactlyInAnyOrder(Position.MID, Position.TOP);
        }

        @Test
        @DisplayName("파티 조건도 함께 간다")
        void carriesThePartyConditions() throws Exception {
            subscribe(2);
            register(request(1L, Position.MID, clock.nowMillis()));
            register(request(2L, Position.TOP, clock.nowMillis() + 1));

            tick.runOnce();

            awaitEvents();
            MatchingEvent event = received.getFirst();
            assertThat(event.queue()).isEqualTo(GameQueue.SOLO_DUO);
            assertThat(event.targetSize()).isEqualTo(2);
            assertThat(event.purpose()).isEqualTo(Purpose.RANK_UP);
            assertThat(event.voiceParty()).isFalse();
        }

        @Test
        @DisplayName("파티가 성립하지 않으면 아무 소식도 없다")
        void staysQuietWhenNothingMatched() throws Exception {
            subscribe(1);
            register(request(1L, Position.MID, clock.nowMillis()));

            tick.runOnce();

            assertThat(arrived.await(1, TimeUnit.SECONDS)).isFalse();
            assertThat(received).isEmpty();
            container.stop();
        }
    }

    @Nested
    @DisplayName("요청 만료")
    class Expired {

        @Test
        @DisplayName("만료된 사람에게 소식이 간다")
        void expiredRequestsAreAnnounced() throws Exception {
            subscribe(1);
            long tooOld = clock.nowMillis() - expiry.maxWait().toMillis() - 1_000;
            register(request(1L, Position.MID, tooOld));

            tick.runOnce();

            awaitEvents();
            assertThat(received).hasSize(1);
            assertThat(received.getFirst().type()).isEqualTo(MatchingEvent.Type.REQUEST_EXPIRED);
            assertThat(received.getFirst().userId()).isEqualTo(1L);
            assertThat(received.getFirst().position()).isNull();
        }
    }

    @Test
    @DisplayName("소식은 JSON으로 오간다")
    void eventsTravelAsJson() throws Exception {
        subscribe(1);

        publisher.publish(new MatchingEvent(
                MatchingEvent.Type.PARTY_CONFIRMED, 7L, 10482L,
                GameQueue.FLEX, 5, Purpose.LEARNING, Position.SUPPORT, true));

        awaitEvents();
        MatchingEvent event = received.getFirst();
        assertThat(event.userId()).isEqualTo(7L);
        assertThat(event.requestId()).isEqualTo(10482L);
        assertThat(event.queue()).isEqualTo(GameQueue.FLEX);
        assertThat(event.position()).isEqualTo(Position.SUPPORT);
        assertThat(event.voiceParty()).isTrue();
    }
}

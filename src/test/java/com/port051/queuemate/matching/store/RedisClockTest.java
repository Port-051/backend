package com.port051.queuemate.matching.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/** 02-technical-spec-supplement 1.5 — 만료 판정은 공용 시계를 본다. */
@SpringBootTest
@Testcontainers
class RedisClockTest {

    @Container
    @ServiceConnection
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Autowired
    RedisClock clock;

    @Test
    @DisplayName("epoch millis를 돌려준다")
    void returnsEpochMillis() {
        long now = clock.nowMillis();

        // 2020-01-01 이후, 그리고 앱 시계와 크게 벌어지지 않는다.
        assertThat(now).isGreaterThan(1_577_836_800_000L);
        assertThat(Math.abs(now - System.currentTimeMillis())).isLessThan(60_000);
    }

    @Test
    @DisplayName("시간은 뒤로 가지 않는다")
    void neverGoesBackwards() {
        long first = clock.nowMillis();

        assertThat(clock.nowMillis()).isGreaterThanOrEqualTo(first);
    }
}

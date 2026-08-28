package com.port051.queuemate.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 진짜 Redis를 띄우고 붙는다.
 *
 * <p>04 6장이 Testcontainers를 고른 이유가 그대로 여기에도 적용된다 —
 * <b>확인하려는 것이 Lua의 원자성과 자료구조의 정렬 규칙</b>이라, 흉내 낸 구현으로 재면
 * 검증 대상 자체가 없어진다. 임베디드 Redis는 Lua를 지원하지 않거나 다르게 지원한다.
 *
 * <p>매칭 루프의 스케줄은 아주 길게 밀어 둔다. 테스트가 원하는 시점에 직접 틱을 돌려야
 * "이 입력에 이 출력"을 확인할 수 있고, 백그라운드 틱이 끼면 결정성 검증이 흔들린다.
 */
@SpringBootTest(
        properties = {
            "queuemate.matching.tick=3600s",
            "queuemate.matching.expiry-sweep=3600s",
            "spring.task.scheduling.pool.size=1"
        })
@Testcontainers
public abstract class RedisTestBase {

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired protected StringRedisTemplate redis;

    /** 테스트마다 깨끗한 상태에서 시작한다. 앞 테스트의 명단이 남으면 순서 검증이 무너진다. */
    @BeforeEach
    void flush() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }
}

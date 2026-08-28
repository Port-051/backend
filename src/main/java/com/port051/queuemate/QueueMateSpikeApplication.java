package com.port051.queuemate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * QueueMate 실시간 매칭 코어 — 스파이크 (이슈 #48).
 *
 * <p>구현 범위는 {@code docs/01-functional-spec-mvp.md} 4장(자동 매칭)과 5장(즉시 매칭 제안·수락)이다.
 * Core API가 아직 없으므로 사용자 요청을 받는 REST 컨트롤러와 SSE도 여기 임시로 둔다
 * ({@code docs/05-realtime-matching-contract.md} "이번 단계의 범위").
 *
 * <p><b>DB를 보지 않는다.</b> 판정에 필요한 조건이 {@code req:} JSON 안에 전부 있어서
 * 다른 데를 볼 이유가 없다. JPA · Flyway · PostgreSQL은 의존성에 넣지 않았다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class QueueMateSpikeApplication {

    public static void main(String[] args) {
        SpringApplication.run(QueueMateSpikeApplication.class, args);
    }
}

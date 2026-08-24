package com.port051.queuemate.matching;

import com.port051.queuemate.matching.config.MatchingProperties;
import com.port051.queuemate.matching.domain.CandidateFilter;
import com.port051.queuemate.matching.domain.TierPolicy;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 실시간(즉시) 매칭 서비스 — 스파이크 #35.
 *
 * <p>공통 골격이다. 매칭 루프·좌석 선점·제안 수명은 <b>비교 축</b>이므로 각자 브랜치에서 짠다.
 * 규약 10절 "시작 전에 고정할 것"을 본다.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(MatchingProperties.class)
public class RealtimeMatchingApplication {

    public static void main(String[] args) {
        SpringApplication.run(RealtimeMatchingApplication.class, args);
    }

    @Bean
    CandidateFilter candidateFilter(TierPolicy tierPolicy) {
        return new CandidateFilter(tierPolicy);
    }
}

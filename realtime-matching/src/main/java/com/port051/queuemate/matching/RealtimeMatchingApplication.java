package com.port051.queuemate.matching;

import com.port051.queuemate.matching.config.MatchingProperties;
import com.port051.queuemate.matching.domain.CandidateFilter;
import com.port051.queuemate.matching.domain.PartyComposer;
import com.port051.queuemate.matching.domain.TierPolicy;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 실시간(즉시) 매칭 서비스 — 스파이크 #35.
 *
 * <p>이 실행기는 <b>Redis만 본다.</b> DB 커넥션을 쓰지 않는 것이 설계의 전제이고,
 * 그래서 판정에 필요한 모든 조건이 요청 메모(JSON) 안에 들어 있다.
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

    @Bean
    PartyComposer partyComposer(CandidateFilter filter, MatchingProperties properties) {
        return new PartyComposer(filter, properties.searchBudget());
    }
}

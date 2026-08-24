package com.port051.queuemate.matching.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 스파이크의 자유 변수는 전부 여기 모은다. 판정 시나리오를 돌릴 때 바꾸는 값이
 * 코드에 흩어져 있으면 "어느 설정으로 잰 수치인가"를 리포트에 적을 수 없다(02 6.5).
 */
@ConfigurationProperties(prefix = "matching")
public record MatchingProperties(
        /** 02 1.4 — 실행 주기 초기값은 1초. 성립률 곡선과 함께 조정한다. */
        Duration cycleInterval,
        /** 기동 후 첫 사이클까지의 지연. 테스트에서 루프를 재우는 데 쓴다. */
        Duration initialDelay,
        /** 한 사이클에 훑는 대기 명단의 길이. */
        int scanWindow,
        /** 한 사이클에 만드는 제안의 상한. */
        int maxOffersPerCycle,
        /** 조합 탐색 예산. 초과하면 그 사이클은 포기하고 다음 사이클에서 다시 본다. */
        int searchBudget,
        /** 01 5.2 — 수락 제한시간. 제안 생성 시각 기준이다(02 3.1). */
        Duration acceptWindow,
        /** 01 5.4 — 거절로 깨진 조합의 재제안 억제 기간. */
        Duration comboSuppression,
        /** 01 3.1 — 최대 대기시간의 허용 범위. */
        Duration minMaxWait,
        Duration maxMaxWait,
        /** SSE 하트비트 주기. */
        Duration heartbeat,
        /** 01 3.5 — 게임 규칙상 티어 제약. 코드에 고정하지 않는다. */
        TierRule tierRule
) {

    /**
     * 디비전 단위 순서값(0 = 아이언 IV)으로 구간을 잘라 허용 폭을 준다.
     * 01 3.5의 "낮은 구간은 여러 단계 / 중간은 한 티어 위아래 / 상위는 디비전 단위"를
     * 하나의 형태로 표현한 것이다. 실제 값은 시즌마다 바뀌므로 설정에서 온다.
     */
    public record TierRule(
            /** 이 순서값 이상은 솔로·듀오 요청을 만들 수 없다 (01 3.6 — 마스터 이상). */
            int soloDuoBlockedFromOrder,
            List<Band> soloDuoBands
    ) {
        public record Band(int upToOrder, int spread) {
        }
    }
}

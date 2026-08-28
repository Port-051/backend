package com.port051.queuemate.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 매칭 루프와 제안 수명의 설정값. <b>전부 계약 밖 자율 영역이다</b>(05 "자율").
 * 판정일에 이 값들이 비교 대상이 되므로 코드에 흩어놓지 않고 여기 모은다.
 *
 * @param tick          매칭 루프 주기
 * @param batchSize     한 틱에 대기 명단에서 읽는 최대 인원
 * @param claimTtl      선점 유지 시간
 * @param offerTtl      제안 수락 제한시간 (01 5.1 — 남은 시간을 실시간으로 표시한다)
 * @param maxWait       즉시 매칭 최대 대기시간 (01 10.2)
 * @param suppressTtl   실패 조합 재제안 억제 기간 (01 5.4 — 10분)
 */
@ConfigurationProperties(prefix = "queuemate.matching")
public record MatchingProperties(
        @DefaultValue("200ms") Duration tick,
        @DefaultValue("500") int batchSize,
        @DefaultValue("5s") Duration claimTtl,
        @DefaultValue("20s") Duration offerTtl,
        @DefaultValue("5m") Duration maxWait,
        @DefaultValue("10m") Duration suppressTtl) {

    /**
     * <b>틱 주기를 200ms로 둔 이유.</b> 02 1.4는 초기값을 1초로 제안했지만,
     * 02 6.4의 목표가 "제안 전달 지연 p95 1초 이하"다. 틱이 1초면 최악의 경우 제안 생성만으로
     * 예산을 다 쓰고 전파·수신 시간이 남지 않는다. 200ms면 생성 지연의 p95가 대략 200ms 안에 들어와
     * 나머지 800ms를 전파에 쓸 수 있다.
     *
     * <p>대가는 빈 조회다. 대기자가 없을 때도 초당 5번 명단을 훑는다.
     * 이 비용과 성립률 곡선(02 6.3)을 함께 재서 조정하는 것이 판정일의 비교 축 하나다.
     */
    public long tickMillis() {
        return tick.toMillis();
    }
}

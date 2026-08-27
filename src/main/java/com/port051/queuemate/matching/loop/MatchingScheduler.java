package com.port051.queuemate.matching.loop;

import com.port051.queuemate.matching.domain.Party;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 매칭을 언제 돌릴지 정한다. 02-technical-spec-supplement 1.4.
 *
 * <p>즉시 매칭은 예약 매칭과 달리 실행 시점이 정해져 있지 않다. 문서는 각 인스턴스가
 * <b>주기적으로 대기 요청을 훑는 틱 루프</b>를 돌게 하고, 주기는 재 보고 정하되
 * 초기값을 1초로 두라고 한다.
 *
 * <p>{@code fixedDelay}를 쓴다. {@code fixedRate}는 한 바퀴가 주기보다 오래 걸리면
 * 다음 바퀴가 겹쳐 들어와 같은 명단을 두 번 훑게 된다.
 * {@code fixedDelay}는 끝난 뒤부터 세므로 겹치지 않는다.
 *
 * <p>테스트에서 끌 수 있게 해 둔 이유는, 배경에서 도는 루프가 있으면 테스트가 준비한 명단을
 * 테스트가 확인하기 전에 비워 버리기 때문이다.
 */
@Component
@ConditionalOnProperty(name = "queuemate.matching.scheduled", havingValue = "true", matchIfMissing = true)
public class MatchingScheduler {

    private static final Logger log = LoggerFactory.getLogger(MatchingScheduler.class);

    private final MatchingTick tick;

    public MatchingScheduler(MatchingTick tick) {
        this.tick = tick;
    }

    @Scheduled(fixedDelayString = "${queuemate.matching.tick:1s}")
    public void tick() {
        try {
            List<Party> confirmed = tick.runOnce();
            if (!confirmed.isEmpty()) {
                log.info("이번 바퀴에 성립한 파티: {}", confirmed.size());
            }
        } catch (RuntimeException e) {
            // 한 바퀴가 실패해도 루프는 계속 돌아야 한다. 여기서 새어 나가면 스케줄러가 멈춘다.
            log.error("매칭 한 바퀴가 실패했다", e);
        }
    }
}

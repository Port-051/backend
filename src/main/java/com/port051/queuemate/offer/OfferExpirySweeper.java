package com.port051.queuemate.offer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 제안 만료. 02 3.3이 요구한 <b>두 경로 중 하나</b>다.
 *
 * <pre>
 *   예약된 만료 작업          ← 이 클래스
 *   응답 시점의 지연 판정      ← respond_offer.lua 안의 expiresAt 검사
 * </pre>
 *
 * <p>두 경로가 동시에 실행돼도 상태 전이는 한 번만 일어난다. 양쪽 다 Lua 안에서
 * {@code status == 'PENDING'}일 때만 쓰기 때문이다.
 *
 * <p>스위퍼가 필요한 이유는 아무도 응답하지 않는 제안 때문이다. 지연 판정만 두면
 * 응답이 영영 오지 않는 제안이 참가자를 명단 밖에 붙들어 둔 채 남는다.
 */
@Component
public class OfferExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(OfferExpirySweeper.class);
    private static final int BATCH = 200;

    private final OfferStore offers;
    private final OfferService offerService;

    public OfferExpirySweeper(OfferStore offers, OfferService offerService) {
        this.offers = offers;
        this.offerService = offerService;
    }

    @Scheduled(fixedDelayString = "${queuemate.matching.expiry-sweep:200ms}")
    public void sweep() {
        long now = System.currentTimeMillis();
        for (long offerId : offers.expiredOfferIds(now, BATCH)) {
            try {
                if (!offers.expire(offerId, now)) continue; // 다른 인스턴스가 먼저 닫았다
                offers.load(offerId).ifPresent(offerService::handleExpired);
            } catch (Exception e) {
                log.warn("제안 만료 처리 실패 offerId={}", offerId, e);
            }
        }
    }
}

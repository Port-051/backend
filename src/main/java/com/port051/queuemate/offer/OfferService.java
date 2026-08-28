package com.port051.queuemate.offer;

import com.port051.queuemate.contract.MatchRequestPayload;
import com.port051.queuemate.contract.Position;
import com.port051.queuemate.contract.RequestState;
import com.port051.queuemate.matching.MatchRequestStore;
import com.port051.queuemate.result.PartyRecorder;
import com.port051.queuemate.sse.EventFanout;
import com.port051.queuemate.sse.EventType;
import com.port051.queuemate.sse.MatchEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 제안 수명. 01 5장 전체.
 *
 * <p>제안이 만들어진 뒤 확정·거절·만료로 끝날 때까지의 상태 전이를 여기 모았다.
 * 매칭 루프는 "누구를 묶을지"만 정하고, "그 뒤 어떻게 되는지"는 이 클래스가 안다.
 */
@Service
public class OfferService {

    private static final Logger log = LoggerFactory.getLogger(OfferService.class);

    private final OfferStore offers;
    private final MatchRequestStore requests;
    private final ComboSuppressor suppressor;
    private final PartyRecorder parties;
    private final EventFanout fanout;

    public OfferService(
            OfferStore offers,
            MatchRequestStore requests,
            ComboSuppressor suppressor,
            PartyRecorder parties,
            EventFanout fanout) {
        this.offers = offers;
        this.requests = requests;
        this.suppressor = suppressor;
        this.parties = parties;
        this.fanout = fanout;
    }

    /**
     * 제안을 만들어 참가자 전원에게 <b>동시에</b> 보낸다. 01 5.1.
     *
     * <p>명단에서 먼저 빼고 이벤트를 나중에 보낸다. 순서를 뒤집으면 이벤트를 받은 참가자가
     * 수락을 눌렀을 때 아직 명단에 남아 있어, 다른 매처가 같은 사람을 또 집는 창이 열린다.
     *
     * <p>상대방의 Riot ID와 Discord 계정은 확정 전까지 공개하지 않는다(01 5.1).
     * 이 스파이크의 계약 JSON에는 애초에 그 필드가 없다.
     */
    public Offer propose(List<MatchRequestPayload> party, Map<Long, Position> positions, long now) {
        long offerId = offers.nextOfferId();
        Offer offer = offers.create(offerId, party, positions, now);

        party.forEach(member -> requests.detachForOffer(member, offerId));

        List<MatchEvent> events = new ArrayList<>(party.size());
        for (MatchRequestPayload member : party) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("queue", offer.queue().name());
            data.put("targetSize", offer.targetSize());
            data.put("position", positions.get(member.requestId()).name());
            data.put("expiresAt", offer.expiresAt());
            data.put("acceptedCount", 0);
            events.add(
                    MatchEvent.of(
                            EventType.OFFER_CREATED, member.userId(), member.requestId(), offerId, data));
        }
        fanout.publishAll(events);
        return offer;
    }

    /**
     * 수락·거절. 01 5.2 · 5.3.
     *
     * <p>기록과 전원 수락 판정은 {@code respond_offer.lua} 안에서 한 번에 일어난다.
     * 이 메서드는 그 결과에 따라 확정하거나 정리할 뿐이다.
     */
    public RespondResult respond(long offerId, long requestId, boolean accept, boolean keepSearching) {
        long now = System.currentTimeMillis();
        Optional<Offer> loaded = offers.load(offerId);
        if (loaded.isEmpty()) return RespondResult.gone("제안을 찾을 수 없다");

        Offer offer = loaded.get();
        if (!offer.members().containsKey(requestId)) {
            return RespondResult.gone("이 제안의 참가자가 아니다");
        }

        String verdict = accept ? "ACCEPTED" : "DECLINED";
        String result = offers.respond(offerId, requestId, verdict, now);
        if (result == null) return RespondResult.gone("제안을 찾을 수 없다");

        if (result.startsWith("ALREADY:")) {
            // 01 5.2 — 중복 클릭이나 네트워크 재전송에도 같은 요청을 한 번만 처리한다.
            // 이미 반영된 것과 같은 결과를 그대로 돌려주면 클라이언트는 성공으로 읽는다.
            return RespondResult.accepted(countAccepted(offerId), true);
        }
        if ("EXPIRED".equals(result)) {
            handleExpired(offer);
            return RespondResult.expired();
        }
        if ("CLOSED".equals(result) || "NOT_PARTICIPANT".equals(result)) {
            return RespondResult.gone("이미 끝난 제안이다");
        }
        if ("DECLINED".equals(result)) {
            handleDeclined(offer, requestId, keepSearching);
            return RespondResult.declined();
        }
        if (result.startsWith("ALL_ACCEPTED:")) {
            confirm(offer, now);
            return RespondResult.accepted(offer.members().size(), false);
        }

        int accepted = Integer.parseInt(result.substring("PENDING:".length()));
        broadcastResponseUpdate(offer, accepted);
        return RespondResult.accepted(accepted, false);
    }

    /** 전원 수락 → 확정. 01 5.3 — 즉시 매칭은 전원이 수락했을 때만 확정한다. */
    private void confirm(Offer offer, long now) {
        List<MatchRequestPayload> party = loadParty(offer);
        if (party.size() != offer.members().size()) {
            log.warn("확정 직전 요청 메모가 사라졌다 offerId={} — 제안을 닫는다", offer.offerId());
            offers.markStatus(offer.offerId(), OfferStatus.EXPIRED);
            requeueAll(party, List.of());
            return;
        }

        PartyRecorder.ConfirmResult result = parties.confirm(party, offer.members(), now);
        if (!result.isConfirmed()) {
            // 제약이 실제로 막은 것이므로 위반이 아니라 정상 경로다.
            // 어느 제약이 막았는지 남긴다 — 판정일에 이 분포가 비교 자료가 된다.
            log.info(
                    "확정이 {}에 막혔다 offerId={} — 참가자를 재대기시킨다",
                    result.blockedBy(),
                    offer.offerId());
            offers.markStatus(offer.offerId(), OfferStatus.EXPIRED);
            requeueAll(party, List.of());
            return;
        }
        long partyId = result.partyId().orElseThrow();

        offers.markStatus(offer.offerId(), OfferStatus.CONFIRMED);

        List<MatchEvent> events = new ArrayList<>(party.size());
        for (MatchRequestPayload member : party) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("position", offer.members().get(member.requestId()).name());
            data.put("size", party.size());
            events.add(
                    MatchEvent.of(
                                    EventType.PARTY_CONFIRMED,
                                    member.userId(),
                                    member.requestId(),
                                    offer.offerId(),
                                    data)
                            .withParty(partyId));
        }
        fanout.publishAll(events);
    }

    /**
     * 명시적 거절로 제안이 깨졌다. 01 5.3 · 5.4.
     *
     * <p>이 조합을 10분간 억제한다 — <b>명시적 거절만</b> 억제 대상이다.
     * 거절한 사람이 누구인지는 다른 참가자에게 공개하지 않는다(01 5.2).
     */
    private void handleDeclined(Offer offer, long declinedRequestId, boolean keepSearching) {
        suppressor.suppress(offer.requestIds());

        List<MatchRequestPayload> party = loadParty(offer);
        List<Long> stopSearching = new ArrayList<>();
        if (!keepSearching) stopSearching.add(declinedRequestId);
        requeueAll(party, stopSearching);

        List<MatchEvent> events = new ArrayList<>();
        for (MatchRequestPayload member : party) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("requeued", !stopSearching.contains(member.requestId()));
            events.add(
                    MatchEvent.of(
                            EventType.OFFER_DECLINED,
                            member.userId(),
                            member.requestId(),
                            offer.offerId(),
                            data));
        }
        fanout.publishAll(events);
    }

    /**
     * 제한시간 초과. 01 5.2 · 10.2.
     *
     * <p><b>억제하지 않는다.</b> 01 5.4 — 시간초과까지 억제하면 알림이 늦게 닿은 참가자 한 명 때문에
     * 정상 조합이 10분간 막힌다. 거절은 의사 표시지만 시간초과는 전달 실패일 수 있다.
     *
     * <p>대가가 있다. 응답하지 않는 참가자가 명단에 남아 있으면 결정적 FCFS가 <b>같은 조합을
     * 다시 만들고 다시 시간초과된다.</b> 이 되풀이는 최대 대기시간(01 10.2)이 그 참가자를
     * 명단에서 걷어낼 때 끝난다 — 즉 한도가 있고, 그 한도가 {@code maxWait}이다.
     * 낭비되는 사이클 수는 판정일에 잴 값이다.
     */
    public void handleExpired(Offer offer) {
        List<MatchRequestPayload> party = loadParty(offer);
        requeueAll(party, List.of());

        List<MatchEvent> events = new ArrayList<>();
        for (MatchRequestPayload member : party) {
            events.add(
                    MatchEvent.of(
                            EventType.OFFER_EXPIRED,
                            member.userId(),
                            member.requestId(),
                            offer.offerId()));
        }
        fanout.publishAll(events);
    }

    /**
     * 참가자를 다시 대기시킨다. 01 5.3 —
     * "이미 수락했던 정상 이용자는 <b>기존 대기 순서를 유지한 채</b> 다시 대기한다."
     *
     * @param stopSearching 재대기하지 않고 종료할 요청 (매칭 종료를 고른 사람)
     */
    private void requeueAll(List<MatchRequestPayload> party, List<Long> stopSearching) {
        for (MatchRequestPayload member : party) {
            if (stopSearching.contains(member.requestId())) {
                requests.setState(member.requestId(), RequestState.CANCELLED);
                continue;
            }
            requests.requeue(member);
        }
    }

    private void broadcastResponseUpdate(Offer offer, int acceptedCount) {
        // 01 5.2 — 수락 후 다른 참가자의 응답 현황을 익명으로 보여준다. 수를 세어 보낼 뿐
        // 누가 수락했는지는 보내지 않는다.
        List<MatchEvent> events = new ArrayList<>();
        offer.userIds()
                .forEach(
                        (requestId, userId) -> {
                            Map<String, Object> data = new LinkedHashMap<>();
                            data.put("acceptedCount", acceptedCount);
                            data.put("total", offer.members().size());
                            events.add(
                                    MatchEvent.of(
                                            EventType.OFFER_RESPONSE_UPDATED,
                                            userId,
                                            requestId,
                                            offer.offerId(),
                                            data));
                        });
        fanout.publishAll(events);
    }

    private int countAccepted(long offerId) {
        return (int) offers.responses(offerId).values().stream().filter("ACCEPTED"::equals).count();
    }

    private List<MatchRequestPayload> loadParty(Offer offer) {
        return requests.loadAll(offer.requestIds());
    }

    /** 응답 처리 결과. 컨트롤러가 상태코드로 옮긴다. */
    public record RespondResult(String outcome, int acceptedCount, boolean idempotent, String message) {
        static RespondResult accepted(int count, boolean idempotent) {
            return new RespondResult("OK", count, idempotent, null);
        }

        static RespondResult declined() {
            return new RespondResult("DECLINED", 0, false, null);
        }

        static RespondResult expired() {
            return new RespondResult("EXPIRED", 0, false, "제한시간이 지났다");
        }

        static RespondResult gone(String message) {
            return new RespondResult("GONE", 0, false, message);
        }
    }
}

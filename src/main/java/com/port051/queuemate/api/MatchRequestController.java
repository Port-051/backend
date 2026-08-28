package com.port051.queuemate.api;

import com.port051.queuemate.api.dto.CreateMatchRequest;
import com.port051.queuemate.api.dto.Responses;
import com.port051.queuemate.contract.MatchRequestPayload;
import com.port051.queuemate.contract.Queue;
import com.port051.queuemate.contract.RequestState;
import com.port051.queuemate.matching.MatchRequestStore;
import com.port051.queuemate.matching.TierRule;
import com.port051.queuemate.offer.OfferStore;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 매칭 요청 접수·조회·취소.
 *
 * <p>원래 기획은 Core API가 프런트 요청을 받아 Redis에 넣는 구조지만, 지금은 Core API가 없어서
 * 그 몫을 실시간 매칭 안에 임시로 둔다(05 "이번 단계의 범위").
 *
 * <p>URL과 응답 모양은 <b>계약이다</b>(이슈 #48 API 계약). 부하 발생기가 꽂히는 표면이라
 * 형태가 다르면 k6 스크립트를 사람 수만큼 쓰게 된다.
 */
@RestController
@RequestMapping("/api")
public class MatchRequestController {

    private final MatchRequestStore requests;
    private final OfferStore offers;
    private final TierRule tierRule;

    public MatchRequestController(MatchRequestStore requests, OfferStore offers, TierRule tierRule) {
        this.requests = requests;
        this.offers = offers;
        this.tierRule = tierRule;
    }

    /** 요청 접수. 01 3장. */
    @PostMapping("/match-requests")
    public ResponseEntity<?> create(@Valid @RequestBody CreateMatchRequest body) {
        Optional<String> rejection = validate(body);
        if (rejection.isPresent()) {
            return ResponseEntity.badRequest().body(new Responses.Conflict(rejection.get(), null));
        }

        long requestId = requests.nextRequestId();
        // 신청 시각은 서버가 찍는다 — 01 4.3의 전순서가 이 값에 걸려 있다.
        long requestedAt = System.currentTimeMillis();
        MatchRequestPayload payload = body.toPayload(requestId, requestedAt);
        requests.enqueue(payload);

        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Location", "/api/match-requests/" + requestId)
                .body(new Responses.Created(requestId, RequestState.WAITING));
    }

    /** 상태 조회. 제안이 진행 중이면 {@code offerId}를 채운다(이슈 #48 API 계약). */
    @GetMapping("/match-requests/{requestId}")
    public ResponseEntity<?> status(@PathVariable long requestId) {
        RequestState state = requests.state(requestId);
        if (state == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(describe(requestId, state));
    }

    /**
     * 취소. 01 3.9.
     *
     * <p>취소와 제안 생성이 동시에 일어나면 상태 전이에 먼저 성공한 쪽이 이긴다.
     * 제안이 이겼으면 <b>409</b>와 함께 그 제안 id를 돌려준다 — 사용자에게 제안이 도착했음을 알리고
     * 거절 경로를 제시해야 하기 때문이다.
     */
    @DeleteMapping("/match-requests/{requestId}")
    public ResponseEntity<?> cancel(@PathVariable long requestId) {
        Optional<MatchRequestPayload> payload = requests.load(requestId);
        RequestState state = requests.state(requestId);
        if (state == null) return ResponseEntity.notFound().build();

        if (payload.isEmpty()) {
            // 메모가 이미 지워졌다 — 확정·취소·실패로 끝난 요청이다.
            Long offerId = requests.currentOffer(requestId).orElse(null);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new Responses.Conflict("대기 중인 요청이 아니다", offerId));
        }

        Queue queue = payload.get().queue();
        MatchRequestStore.CancelResult result =
                requests.cancel(requestId, queue, payload.get().targetSize());

        if (result.cancelled()) {
            return ResponseEntity.ok(new Responses.Cancelled(requestId, RequestState.CANCELLED));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(
                        new Responses.Conflict(
                                result.offerId() != null
                                        ? "제안이 먼저 도착했다. 거절로 처리해야 한다"
                                        : "대기 중인 요청이 아니다",
                                result.offerId()));
    }

    /**
     * 재연결 후 상태 재조회. 02 3.4 —
     * 연결이 끊긴 동안의 이벤트는 재전송하지 않고, 현재 상태 전체를 다시 읽어 화면을 맞춘다.
     */
    @GetMapping("/me/state")
    public ResponseEntity<Responses.UserState> myState(@RequestParam long userId) {
        List<Responses.RequestStatus> statuses = new ArrayList<>();
        for (long requestId : requests.requestIdsOf(userId)) {
            RequestState state = requests.state(requestId);
            if (state == null) continue;
            statuses.add(describe(requestId, state));
        }
        return ResponseEntity.ok(new Responses.UserState(userId, statuses));
    }

    private Responses.RequestStatus describe(long requestId, RequestState state) {
        Long offerId = requests.currentOffer(requestId).orElse(null);
        Long partyId = requests.partyOf(requestId).orElse(null);
        Long expiresAt =
                offerId == null ? null : offers.load(offerId).map(o -> o.expiresAt()).orElse(null);
        return new Responses.RequestStatus(requestId, state, offerId, partyId, expiresAt);
    }

    /**
     * 요청 생성 검증. 01 3.4 · 3.5 · 3.6 · 3.7.
     *
     * <p>여기서 막지 않으면 게임에서 큐를 잡지 못하는 파티가 만들어진다.
     */
    private Optional<String> validate(CreateMatchRequest body) {
        if (!body.queue().allowsSize(body.targetSize())) {
            return Optional.of(
                    "이 큐에서 만들 수 없는 인원이다. 가능한 인원 " + body.queue().allowedSizes());
        }
        if (body.allowedTierMinOrder() > body.allowedTierMaxOrder()) {
            return Optional.of("허용 티어 범위의 하한이 상한보다 높다");
        }
        if (body.queue().isTierRestricted()) {
            // 01 3.6 · 3.7 — 마스터 이상과 언랭크는 솔로·듀오 랭크 요청을 만들 수 없다.
            if (!tierRule.canCreateSoloDuoRequest(body.tierOrder())) {
                return Optional.of("이 티어로는 솔로·듀오 랭크 요청을 만들 수 없다. 자유 랭크나 일반을 쓴다");
            }
            // 01 3.5 — 본인 티어 기준으로 게임 규칙상 가능한 범위 안에서만 고를 수 있다.
            int gap = tierRule.maxGapAt(body.tierOrder());
            if (body.tierOrder() - body.allowedTierMinOrder() > gap
                    || body.allowedTierMaxOrder() - body.tierOrder() > gap) {
                return Optional.of("게임 규칙이 허용하는 티어 범위를 넘었다. 허용 폭 " + gap);
            }
        }
        return Optional.empty();
    }
}

package com.port051.queuemate.api;

import com.port051.queuemate.api.dto.Responses;
import com.port051.queuemate.offer.OfferService;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 제안 수락·거절. 01 5.2.
 *
 * <p>URL과 본문 모양은 계약이다(이슈 #48 API 계약).
 *
 * <p><b>중복 클릭과 네트워크 재전송은 200으로 답한다.</b> 이미 반영된 응답과 같은 결과를
 * 돌려주면 클라이언트는 성공으로 읽는다. 409로 답하면 재전송한 클라이언트가 실패로 알고
 * 다시 누르게 되는데, 그 재시도가 또 409를 받는 고리가 생긴다.
 */
@RestController
@RequestMapping("/api/offers")
public class OfferController {

    private final OfferService offerService;

    public OfferController(OfferService offerService) {
        this.offerService = offerService;
    }

    /** 본문 {@code {"requestId": N}}. */
    public record AcceptBody(@NotNull Long requestId) {}

    /** 본문 {@code {"requestId": N, "keepSearching": true}}. */
    public record DeclineBody(@NotNull Long requestId, Boolean keepSearching) {}

    @PostMapping("/{offerId}/accept")
    public ResponseEntity<Responses.OfferResponse> accept(
            @PathVariable long offerId, @RequestBody AcceptBody body) {
        OfferService.RespondResult result =
                offerService.respond(offerId, body.requestId(), true, true);
        return toResponse(offerId, body.requestId(), result);
    }

    @PostMapping("/{offerId}/decline")
    public ResponseEntity<Responses.OfferResponse> decline(
            @PathVariable long offerId, @RequestBody DeclineBody body) {
        // 01 5.3 — 사용자는 `계속 찾기` 또는 `매칭 종료`를 선택한다. 기본값은 계속 찾기다.
        boolean keepSearching = body.keepSearching() == null || body.keepSearching();
        OfferService.RespondResult result =
                offerService.respond(offerId, body.requestId(), false, keepSearching);
        return toResponse(offerId, body.requestId(), result);
    }

    private ResponseEntity<Responses.OfferResponse> toResponse(
            long offerId, long requestId, OfferService.RespondResult result) {
        Responses.OfferResponse body =
                new Responses.OfferResponse(
                        offerId,
                        requestId,
                        result.outcome(),
                        result.acceptedCount(),
                        result.message());

        // 01 9.2 — 만료된 제안에 응답하면 최신 상태를 다시 보여준다.
        HttpStatus status =
                switch (result.outcome()) {
                    case "GONE" -> HttpStatus.GONE;
                    case "EXPIRED" -> HttpStatus.CONFLICT;
                    default -> HttpStatus.OK;
                };
        return ResponseEntity.status(status).body(body);
    }
}

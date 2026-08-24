package com.port051.queuemate.matching.api;

import com.port051.queuemate.matching.api.dto.RespondRequest;
import com.port051.queuemate.matching.offer.Offer;
import com.port051.queuemate.matching.offer.OfferService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 01 5.2 · 5.3 — 수락·거절과 확정. */
@RestController
@RequestMapping("/api/offers")
public class OfferController {

    private final OfferService offers;

    public OfferController(OfferService offers) {
        this.offers = offers;
    }

    @GetMapping("/{offerId}")
    public Map<String, Object> get(@PathVariable long offerId) {
        Offer offer = offers.require(offerId);
        // 01 5.1 — 상대의 Riot ID·Discord는 확정 전까지 공개하지 않는다. 여기엔 애초에 없다.
        return Map.of(
                "offerId", offer.offerId(),
                "queue", offer.queue(),
                "targetSize", offer.targetSize(),
                "createdAt", offer.createdAt(),
                "expiresAt", offer.expiresAt(),
                "positions", offer.positionsByUserId());
    }

    @PostMapping("/{offerId}/accept")
    public OfferService.RespondResult accept(@PathVariable long offerId,
                                             @Valid @RequestBody RespondRequest command) {
        return offers.respond(offerId, command.requestId(), true, true);
    }

    @PostMapping("/{offerId}/decline")
    public OfferService.RespondResult decline(@PathVariable long offerId,
                                              @Valid @RequestBody RespondRequest command) {
        return offers.respond(offerId, command.requestId(), false, command.keepSearchingOrDefault());
    }
}

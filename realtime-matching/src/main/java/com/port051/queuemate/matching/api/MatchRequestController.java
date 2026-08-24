package com.port051.queuemate.matching.api;

import com.port051.queuemate.matching.api.dto.CreateMatchRequest;
import com.port051.queuemate.matching.api.dto.MatchRequestResponse;
import com.port051.queuemate.matching.domain.GameQueue;
import com.port051.queuemate.matching.domain.MatchRequestView;
import com.port051.queuemate.matching.intake.MatchRequestService;
import com.port051.queuemate.matching.redis.EventPublisher;
import com.port051.queuemate.matching.redis.MatchingEvent;
import com.port051.queuemate.matching.redis.RequestRepository;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
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

/** 사용자 요청을 받아 Redis 대기 명단에 넣는 입구. */
@RestController
@RequestMapping("/api/match-requests")
public class MatchRequestController {

    private final MatchRequestService service;
    private final RequestRepository requests;
    private final EventPublisher events;

    public MatchRequestController(MatchRequestService service,
                                  RequestRepository requests,
                                  EventPublisher events) {
        this.service = service;
        this.requests = requests;
        this.events = events;
    }

    @PostMapping
    public ResponseEntity<MatchRequestResponse> create(@Valid @RequestBody CreateMatchRequest command) {
        MatchRequestView request = service.create(command);
        long waiting = service.waitingCount(request.queue(), request.targetSize());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(MatchRequestResponse.waiting(request, waiting));
    }

    @GetMapping("/{requestId}")
    public MatchRequestResponse get(@PathVariable long requestId) {
        MatchRequestView request = service.require(requestId);
        Long offerId = requests.claimedBy(requestId).orElse(null);
        return new MatchRequestResponse(
                request.requestId(),
                offerId == null ? MatchRequestResponse.WAITING : MatchRequestResponse.OFFERED,
                request.requestedAt(),
                request.expiresAt(),
                service.waitingCount(request.queue(), request.targetSize()),
                offerId);
    }

    /**
     * 01 3.9 — 취소와 제안 생성이 동시에 일어나면 먼저 성공한 쪽이 이긴다.
     * 제안이 이겼으면 409를 주고 클라이언트가 거절 경로를 안내한다.
     */
    @DeleteMapping("/{requestId}")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable long requestId) {
        MatchRequestView request = service.require(requestId);
        if (!requests.cancel(request)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "message", "제안이 이미 도착했다. 거절로 진행한다",
                    "offerId", requests.claimedBy(requestId).orElse(-1L)));
        }
        events.publish(MatchingEvent.REQUEST_CANCELLED, List.of(request.userId()),
                Map.of("requestId", requestId));
        return ResponseEntity.ok(Map.of("requestId", requestId, "state", "취소됨"));
    }

    /** 01 0.1 — 조건 입력 화면에 현재 대기 인원을 표시한다. */
    @GetMapping("/waiting")
    public Map<String, Object> waiting(@RequestParam GameQueue queue, @RequestParam int targetSize) {
        return Map.of("queue", queue, "targetSize", targetSize,
                "waitingCount", service.waitingCount(queue, targetSize));
    }
}

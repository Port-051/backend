package com.port051.queuemate.matching.api;

import com.port051.queuemate.matching.api.dto.CreateMatchRequest;
import com.port051.queuemate.matching.api.dto.MatchRequestResponse;
import com.port051.queuemate.matching.domain.GameQueue;
import com.port051.queuemate.matching.domain.MatchRequestView;
import com.port051.queuemate.matching.intake.MatchRequestService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자 요청을 받아 Redis 대기 명단에 넣는 입구.
 *
 * <p>취소({@code DELETE})는 여기에 없다. 취소는 선점과 경합하는 상태 전이라
 * 선점을 어떻게 표현하느냐에 딸려 있다 — 비교 축이다. 요청·응답 형태는 이슈에 적어 맞추고
 * 구현은 각자 브랜치에서 한다.
 */
@RestController
@RequestMapping("/api/match-requests")
public class MatchRequestController {

    private final MatchRequestService service;

    public MatchRequestController(MatchRequestService service) {
        this.service = service;
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
        // 제안 진행 상태(state·offerId)는 선점 방식에 딸린 값이라 비교 축이다.
        // 각자 브랜치에서 이 자리에 채운다 — EventStreamController.state()와 같다.
        return MatchRequestResponse.waiting(
                request, service.waitingCount(request.queue(), request.targetSize()));
    }

    /** 01 0.1 — 조건 입력 화면에 현재 대기 인원을 표시한다. */
    @GetMapping("/waiting")
    public Map<String, Object> waiting(@RequestParam GameQueue queue, @RequestParam int targetSize) {
        return Map.of("queue", queue, "targetSize", targetSize,
                "waitingCount", service.waitingCount(queue, targetSize));
    }
}

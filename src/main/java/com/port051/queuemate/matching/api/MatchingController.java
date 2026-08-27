package com.port051.queuemate.matching.api;

import com.port051.queuemate.matching.domain.GameQueue;
import com.port051.queuemate.matching.domain.MatchRequest;
import com.port051.queuemate.matching.domain.Partition;
import com.port051.queuemate.matching.registration.AlreadyWaitingException;
import com.port051.queuemate.matching.registration.RequestRegistry;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 매칭 신청·취소 API. 05-realtime-matching-contract "이번 단계의 범위".
 *
 * <p>인증은 없다. 1.1은 Discord 로그인을 요구하지만 그것은 별개 기능이고,
 * 이 단계의 범위는 실시간 매칭이라 {@code userId}를 그대로 받는다.
 */
@RestController
@RequestMapping("/api/match-requests")
public class MatchingController {

    private final RequestRegistry registry;

    public MatchingController(RequestRegistry registry) {
        this.registry = registry;
    }

    /**
     * 매칭을 신청한다.
     *
     * <p>돌려주는 것은 계약 2장이 정한 모양 그대로다. 부하 스크립트가 발급된 요청 ID를
     * 여기서 받아 간다.
     */
    @PostMapping
    public ResponseEntity<MatchRequest> register(@Valid @RequestBody MatchRequestBody body) {
        MatchRequest request = registry.register(body.toNewRequest());
        return ResponseEntity.status(HttpStatus.CREATED).body(request);
    }

    /**
     * 매칭 신청을 취소한다. 3.9 — 대기 중일 때만 가능하다.
     *
     * <p>이미 파티로 확정됐거나 만료된 요청은 찾을 수 없다. 3.9의 "먼저 성공한 쪽이 이긴다"가
     * 여기서 성립한다.
     */
    @DeleteMapping("/{requestId}")
    public ResponseEntity<Void> cancel(@PathVariable long requestId) {
        return registry.cancel(requestId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** 조합의 현재 대기 인원. 3.1이 조건 입력 화면에 표시하라고 한 값이다. */
    @GetMapping("/waiting")
    public Map<String, Object> waiting(@RequestParam GameQueue queue, @RequestParam int targetSize) {
        Partition partition = new Partition(queue, targetSize);
        return Map.of(
                "queue", queue,
                "targetSize", targetSize,
                "waiting", registry.waitingCount(partition));
    }

    /** 9.2 — 같은 사람의 요청이 둘 있으면 안 된다. 이미 대기 중이면 그 사실을 알린다. */
    @ExceptionHandler(AlreadyWaitingException.class)
    public ResponseEntity<Map<String, Object>> alreadyWaiting(AlreadyWaitingException e) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("error", "이미 대기 중인 요청이 있다");
        body.put("userId", e.userId());
        body.put("requestId", e.requestId());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /** 값끼리 엮인 규칙을 어겼을 때. 큐와 인원의 조합, 티어 범위 따위다. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    /** 값 하나만 보고 거를 수 있는 것을 어겼을 때. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalidBody(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("요청 본문이 올바르지 않다");
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}

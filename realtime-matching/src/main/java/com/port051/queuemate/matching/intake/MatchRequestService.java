package com.port051.queuemate.matching.intake;

import com.port051.queuemate.matching.api.dto.CreateMatchRequest;
import com.port051.queuemate.matching.domain.GameQueue;
import com.port051.queuemate.matching.domain.MatchRequestView;
import com.port051.queuemate.matching.domain.Position;
import com.port051.queuemate.matching.domain.TierPolicy;
import com.port051.queuemate.matching.redis.Keys;
import com.port051.queuemate.matching.redis.RedisClock;
import com.port051.queuemate.matching.redis.RequestRepository;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;

/**
 * 요청 접수. 실제 아키텍처에서는 Core API가 하는 일이다 —
 * 프런트엔드의 요청을 받아 Redis 대기열에 넣는다(11.2).
 * 스파이크 범위가 실시간 매칭 + Discord뿐이라 그 입구를 여기에 둔다.
 */
@Service
public class MatchRequestService {

    private final RequestRepository requests;
    private final RedisClock clock;
    private final TierPolicy tierPolicy;

    public MatchRequestService(RequestRepository requests, RedisClock clock, TierPolicy tierPolicy) {
        this.requests = requests;
        this.clock = clock;
        this.tierPolicy = tierPolicy;
    }

    public MatchRequestView create(CreateMatchRequest command) {
        validate(command);
        long now = clock.nowMillis();
        MatchRequestView request = new MatchRequestView(
                requests.nextRequestId(),
                command.userId(),
                command.queue(),
                command.targetSize(),
                command.purpose(),
                command.playMinutes(),
                command.voiceMode(),
                command.primaryPosition(),
                command.subPositionsOrEmpty(),
                command.tierOrder(),
                command.allowedTierMinOrder(),
                command.allowedTierMaxOrder(),
                now,
                now + Duration.ofMinutes(command.maxWaitMinutes()).toMillis());
        requests.enqueue(request);
        return request;
    }

    public MatchRequestView require(long requestId) {
        return requests.find(requestId)
                .orElseThrow(() -> new NoSuchElementException("요청을 찾을 수 없다: " + requestId));
    }

    public long waitingCount(GameQueue queue, int targetSize) {
        return requests.waitingCount(Keys.partition(queue, targetSize));
    }

    /** 01 3.4 · 3.5 · 3.6 · 3.7 — 요청 자체가 성립하는지. */
    private void validate(CreateMatchRequest command) {
        GameQueue queue = command.queue();
        if (!queue.allowsSize(command.targetSize())) {
            throw new IllegalArgumentException(
                    "%s 큐가 허용하는 목표 인원은 %s다".formatted(queue, queue.allowedSizes()));
        }
        List<Position> subs = command.subPositionsOrEmpty();
        if (subs.contains(command.primaryPosition())) {
            throw new IllegalArgumentException("부 포지션에 주 포지션을 다시 넣을 수 없다");
        }
        if (queue.gameTierRuleApplies()) {
            if (!tierPolicy.eligibleForSoloDuo(command.tierOrder())) {
                // 01 3.6 마스터 이상 · 01 3.7 언랭크·배치 중
                throw new IllegalArgumentException(
                        "이 티어로는 솔로·듀오 요청을 만들 수 없다. 자유 랭크와 일반 큐를 이용한다");
            }
            int spread = tierPolicy.allowedSpread(queue, command.tierOrder());
            int tier = command.tierOrder();
            if (command.allowedTierMinOrder() != null && command.allowedTierMinOrder() < tier - spread) {
                throw new IllegalArgumentException("허용 티어 하한이 게임 규칙 범위를 벗어난다");
            }
            if (command.allowedTierMaxOrder() != null && command.allowedTierMaxOrder() > tier + spread) {
                throw new IllegalArgumentException("허용 티어 상한이 게임 규칙 범위를 벗어난다");
            }
        }
        if (command.allowedTierMinOrder() != null && command.allowedTierMaxOrder() != null
                && command.allowedTierMinOrder() > command.allowedTierMaxOrder()) {
            throw new IllegalArgumentException("허용 티어 범위의 하한이 상한보다 크다");
        }
    }
}

package com.port051.queuemate.matching.store;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.port051.queuemate.matching.domain.MatchRequest;
import com.port051.queuemate.matching.domain.Partition;
import com.port051.queuemate.matching.sse.EventPublisher;
import com.port051.queuemate.matching.sse.MatchingEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 최대 대기시간이 지난 즉시 매칭 요청을 종료한다. 01-functional-spec-mvp 10.2.
 *
 * <p><b>최대 대기시간을 설정에서 읽는 이유가 있다.</b> 3.1은 사용자가 직접 고르는 값으로 정의하지만
 * 실시간 매칭 계약(05 2장)이 고정한 필드 열셋에 그 값이 없다. 계약은 부하 스크립트가 물고 있어
 * 필드를 늘릴 수 없으므로, 이 단계에서는 서버가 하나로 정한다.
 * 계약에 필드가 생기면 요청별 값으로 바꾸면 되고, 판정 지점은 여기 그대로다.
 *
 * <p>기준 시각은 {@link RedisClock}에서 받는다. 인스턴스마다 다른 시계를 보면
 * 같은 요청이 어디서 판정되느냐에 따라 만료 여부가 갈린다(02 1.5).
 */
@Component
public class RequestExpiry {

    private final WaitingList waitingList;
    private final RequestStore requestStore;
    private final UserGuard userGuard;
    private final EventPublisher events;
    private final RedisClock clock;
    private final Duration maxWait;

    public RequestExpiry(WaitingList waitingList,
                         RequestStore requestStore,
                         UserGuard userGuard,
                         EventPublisher events,
                         RedisClock clock,
                         @Value("${queuemate.matching.max-wait:5m}") Duration maxWait) {
        this.waitingList = waitingList;
        this.requestStore = requestStore;
        this.userGuard = userGuard;
        this.events = events;
        this.clock = clock;
        this.maxWait = maxWait;
    }

    /**
     * 만료된 요청을 명단과 메모에서 지운다.
     *
     * <p>명단을 먼저 지우고 메모를 지운다. 순서가 반대면 메모가 없는 항목이 잠깐 명단에 남아
     * 매칭이 조건을 읽지 못하는 요청을 만나게 된다.
     *
     * <p>두 인스턴스가 동시에 이 메서드를 부르면 같은 요청을 둘 다 지우려 할 수 있다.
     * 지우기는 두 번 해도 결과가 같으므로 정합성 문제는 없고, 낭비만 생긴다.
     * 다만 만료를 사용자에게 알리는 일(4.5)이 붙으면 중복 발송이 되므로,
     * 그때는 지운 쪽만 알리도록 원자적으로 바꿔야 한다.
     *
     * @return 만료 처리된 요청 ID
     */
    public List<Long> sweep() {
        long threshold = clock.nowMillis() - maxWait.toMillis();

        List<Long> expired = new ArrayList<>();
        for (Partition partition : Partition.all()) {
            expired.addAll(sweep(partition, threshold));
        }
        return List.copyOf(expired);
    }

    /** 한 조합만 정리한다. 인스턴스가 조합을 나눠 맡게 되면 이쪽을 부른다. */
    public List<Long> sweep(Partition partition, long threshold) {
        // 알리려면 어떤 조건이었는지, 대기 자리를 비우려면 누구 것인지 알아야 하는데
        // 그 정보가 메모 안에만 있다. 명단에서 빼기 전에 읽어 둔다.
        List<MatchRequest> candidates = requestStore.findAll(waitingList.requestIdsUpTo(partition, threshold));

        // 실제로 지운 것만 돌아온다. 다른 인스턴스가 먼저 지웠으면 빈 목록이다.
        List<Long> swept = waitingList.sweepUpTo(partition, threshold);
        if (swept.isEmpty()) {
            return List.of();
        }

        // 뒤따르는 일은 전부 "내가 지운 것"을 기준으로 한다. 본 것을 기준으로 하면
        // 두 인스턴스가 같은 요청을 함께 처리하고 같은 소식을 두 번 보낸다.
        Set<Long> sweptIds = Set.copyOf(swept);
        List<MatchRequest> expired = candidates.stream()
                .filter(request -> sweptIds.contains(request.requestId()))
                .toList();

        requestStore.deleteAll(swept);
        userGuard.releaseAll(expired.stream().map(MatchRequest::userId).toList());
        // 정리가 끝난 뒤에 알린다(02 3.2). 지운 쪽만 알리므로 같은 소식이 두 번 가지 않는다.
        events.publishAll(expired.stream().map(MatchingEvent::expired).toList());
        return swept;
    }

    /** 지금 기준의 만료 경계. 이 시각 이전에 신청한 요청이 만료 대상이다. */
    public long threshold() {
        return clock.nowMillis() - maxWait.toMillis();
    }

    /** 설정된 최대 대기시간. */
    public Duration maxWait() {
        return maxWait;
    }
}

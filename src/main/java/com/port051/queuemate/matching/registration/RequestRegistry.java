package com.port051.queuemate.matching.registration;

import com.port051.queuemate.matching.domain.MatchRequest;
import com.port051.queuemate.matching.domain.Partition;
import com.port051.queuemate.matching.store.RedisClock;
import com.port051.queuemate.matching.store.RequestIds;
import com.port051.queuemate.matching.store.RequestStore;
import com.port051.queuemate.matching.store.UserGuard;
import com.port051.queuemate.matching.store.WaitingList;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 매칭 요청을 접수하고 취소한다. 05-realtime-matching-contract "이번 단계의 범위".
 *
 * <p>계약은 원래 Core API가 할 일이라고 적어 두고, 이 단계에서는 실시간 매칭 안에 임시로 둔다고 정했다.
 * 매칭 루프가 읽을 수 있는 상태를 만드는 것이 전부이며 판정에는 관여하지 않는다.
 *
 * <p><b>넣는 순서와 지우는 순서가 반대다.</b> 넣을 때는 메모를 먼저 쓰고 명단에 올린다.
 * 명단이 먼저면 조건을 읽을 수 없는 요청이 잠깐 후보가 된다. 지울 때는 명단을 먼저 빼고 메모를 지운다.
 * 어느 쪽이든 <b>명단에 있는 요청은 항상 메모가 있다</b>는 상태를 유지하는 순서다.
 */
@Service
public class RequestRegistry {

    private final RequestIds requestIds;
    private final RequestStore requestStore;
    private final WaitingList waitingList;
    private final UserGuard userGuard;
    private final RedisClock clock;

    public RequestRegistry(RequestIds requestIds,
                           RequestStore requestStore,
                           WaitingList waitingList,
                           UserGuard userGuard,
                           RedisClock clock) {
        this.requestIds = requestIds;
        this.requestStore = requestStore;
        this.waitingList = waitingList;
        this.userGuard = userGuard;
        this.clock = clock;
    }

    /**
     * 요청을 접수한다.
     *
     * <p>요청 ID와 신청 시각은 <b>서버가 정한다.</b> 둘 다 4.3의 전순서를 이루는 값이라
     * 클라이언트가 보내게 두면 먼저 신청한 사람을 제칠 수 있다. 신청 시각은 인스턴스 시계가 아니라
     * 공용 시계에서 받는다(02 1.5).
     *
     * @throws AlreadyWaitingException 같은 사용자가 이미 대기 중일 때
     */
    public MatchRequest register(NewRequest newRequest) {
        long requestId = requestIds.next();

        if (!userGuard.take(newRequest.userId(), requestId)) {
            throw new AlreadyWaitingException(
                    newRequest.userId(), userGuard.waitingRequestId(newRequest.userId()).orElse(null));
        }

        MatchRequest request = newRequest.toMatchRequest(requestId, clock.nowMillis());
        requestStore.save(request);
        waitingList.add(request);
        return request;
    }

    /**
     * 요청을 취소한다. 3.9 — 대기 중일 때만 취소할 수 있다.
     *
     * <p>이미 파티로 확정돼 명단에서 빠졌으면 메모도 없으므로 취소할 것이 없다.
     * 3.9가 정한 "먼저 성공한 쪽이 이긴다"가 여기서 성립한다.
     *
     * @return 실제로 취소했으면 참
     */
    public boolean cancel(long requestId) {
        Optional<MatchRequest> request = requestStore.find(requestId);
        if (request.isEmpty()) {
            return false;
        }

        MatchRequest found = request.get();
        waitingList.remove(Partition.of(found), requestId);
        requestStore.delete(requestId);
        userGuard.release(found.userId());
        return true;
    }

    /** 조합의 현재 대기 인원. 3.1이 조건 입력 화면에 표시하라고 한 값이다. */
    public long waitingCount(Partition partition) {
        return waitingList.size(partition);
    }
}

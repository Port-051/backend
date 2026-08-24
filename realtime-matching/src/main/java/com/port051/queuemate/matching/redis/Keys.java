package com.port051.queuemate.matching.redis;

import com.port051.queuemate.matching.domain.GameQueue;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Redis 키 설계. 실시간 매칭이 보는 것은 이 넷뿐이다.
 *
 * <pre>
 *   mq:{queue}:{targetSize}   ZSET    대기 명단. score = requestedAt(ms), member = requestId
 *   req:{requestId}           STRING  주문 메모(JSON). 판정에 필요한 조건 전부
 *   claim:{requestId}         STRING  "배정 중" 포스트잇. value = offerId, TTL = 수락 제한시간
 *   claim:u:{userId}          STRING  같은 사람이 두 제안에 동시에 들어가는 것을 막는다 (INV-2 근사)
 * </pre>
 *
 * <p>큐와 목표 인원을 키에 넣어 파티션을 나눈 이유는 01 4.1이 큐·목표 인원이 다른 요청을
 * 애초에 후보에서 제외하기 때문이다. 다른 파티션끼리는 경합 자체가 없다.
 *
 * <p>나머지는 제안 진행 상태다 — 대기 명단이 아니라 제안 하나의 수명에 붙는다.
 */
public final class Keys {

    public static final String SEQ_REQUEST = "seq:request";
    public static final String SEQ_OFFER = "seq:offer";
    public static final String SEQ_PARTY = "seq:party";

    /** 활성 파티션 목록. 매칭 루프가 이걸 훑는다. */
    public static final String PARTITIONS = "mq:index";
    /** 제안 만료 스윕용. score = expiresAt(ms) */
    public static final String OFFER_INDEX = "offer:index";
    /** 02 3.2 — 파티 확정·제안 생성은 커밋 후 Pub/Sub으로 발행한다. */
    public static final String EVENT_CHANNEL = "matching.events";

    private Keys() {
    }

    public static String partition(GameQueue queue, int targetSize) {
        return "mq:" + queue.name() + ":" + targetSize;
    }

    public static GameQueue queueOf(String partitionKey) {
        return GameQueue.valueOf(partitionKey.split(":")[1]);
    }

    public static int targetSizeOf(String partitionKey) {
        return Integer.parseInt(partitionKey.split(":")[2]);
    }

    public static String request(long requestId) {
        return "req:" + requestId;
    }

    public static String claim(long requestId) {
        return "claim:" + requestId;
    }

    public static String userClaim(long userId) {
        return "claim:u:" + userId;
    }

    public static String offer(long offerId) {
        return "offer:" + offerId;
    }

    public static String offerResponses(long offerId) {
        return "offer:" + offerId + ":resp";
    }

    /** 제안 종료를 한 번만 일어나게 하는 가드. 값은 종료 사유. */
    public static String offerOutcome(long offerId) {
        return "offer:" + offerId + ":outcome";
    }

    /** 01 5.4 — 실패 조합 재제안 억제. TTL 10분. */
    public static String combo(Collection<Long> requestIds) {
        return "combo:" + requestIds.stream().sorted()
                .map(String::valueOf).collect(Collectors.joining("-"));
    }

    /** 재연결 후 상태 재조회(01 8.1 · 02 3.4)를 위한 사용자별 인덱스. */
    public static String userRequests(long userId) {
        return "user:" + userId + ":req";
    }
}

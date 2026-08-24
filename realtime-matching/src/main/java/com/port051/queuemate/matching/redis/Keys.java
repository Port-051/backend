package com.port051.queuemate.matching.redis;

import com.port051.queuemate.matching.domain.GameQueue;

/**
 * Redis 키 설계. 실시간 매칭이 보는 것은 이 셋뿐이다.
 *
 * <pre>
 *   mq:{queue}:{targetSize}   ZSET    대기 명단. score = requestedAt(ms), member = requestId
 *   req:{requestId}           STRING  주문 메모(JSON). 판정에 필요한 조건 전부
 *   claim:{requestId}         STRING  "배정 중" 포스트잇. 파티를 짜는 중이니 건드리지 마
 * </pre>
 *
 * <p>큐와 목표 인원을 키에 넣어 파티션을 나눈 이유는 01 4.1이 큐·목표 인원이 다른 요청을
 * 애초에 후보에서 제외하기 때문이다. 다른 파티션끼리는 경합 자체가 없다.
 *
 * <p><b>제안 수명에 딸린 키는 여기에 없다.</b> 제안을 어떤 자료구조로 담을지, 응답 현황을
 * 어디에 모을지, 종료 가드를 키로 둘지 Lua 안에서 풀지, 실패 조합 억제(01 5.4)를 키 존재로
 * 판정할지가 전부 비교 축이다. 선점을 사용자 단위로도 걸지(INV-2 근사) 역시 마찬가지다.
 * 각자 브랜치에서 필요한 키를 정의한다.
 */
public final class Keys {

    public static final String SEQ_REQUEST = "seq:request";

    /** 활성 파티션 목록. 매칭 루프가 이걸 훑는다. */
    public static final String PARTITIONS = "mq:index";

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

    /** 재연결 후 상태 재조회(01 8.1 · 02 3.4)를 위한 사용자별 인덱스. */
    public static String userRequests(long userId) {
        return "user:" + userId + ":req";
    }
}

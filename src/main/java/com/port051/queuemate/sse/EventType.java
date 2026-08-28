package com.port051.queuemate.sse;

/**
 * SSE 이벤트 이름. <b>계약이다</b> — 이슈 #48이 일곱 개를 고정했다.
 * 부하 스크립트가 이 이름으로 이벤트를 기다리므로 바꾸거나 늘리지 않는다.
 */
public enum EventType {
    /** 제안이 만들어져 참가자 전원에게 동시에 나간다. 01 5.1 */
    OFFER_CREATED,
    /** 누군가 수락했다. 응답 현황은 익명으로 보낸다. 01 5.2 */
    OFFER_RESPONSE_UPDATED,
    /** 누군가 거절해 제안이 끝났다. 거절한 사람은 밝히지 않는다. 01 5.2 */
    OFFER_DECLINED,
    /** 수락 제한시간이 지났다. 01 5.2 · 10.2 */
    OFFER_EXPIRED,
    /** 전원 수락으로 파티가 확정됐다. 01 5.3 */
    PARTY_CONFIRMED,
    /** 요청이 취소됐다. 01 3.9 */
    REQUEST_CANCELLED,
    /** 최대 대기시간이 지나 매칭에 실패했다. 01 10.2 */
    MATCH_FAILED
}

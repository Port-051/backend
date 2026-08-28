package com.port051.queuemate.contract;

/**
 * Redis 키 이름. 앞의 셋은 계약(05 1절)이고 나머지는 자율 영역이다.
 *
 * <pre>
 * 계약 — 05 1절이 "Redis에 넣는 건 셋뿐"이라고 고정한 것
 *   mq:{queue}:{targetSize}   대기 명단   누가 먼저 왔는지 순서만
 *   req:{requestId}           주문 메모   조건 전부
 *   claim:{requestId}         배정 중 표시
 *
 * 자율 — 제안 수명(01 5장)과 판정 게이트를 세우려고 내가 추가한 것
 *   offer:{offerId}            제안 본문
 *   offer:{offerId}:responses  참가자별 수락·거절
 *   reqoffer:{requestId}       요청 → 진행 중인 제안 (GET 상태 조회용)
 *   reqstate:{requestId}       요청의 현재 상태
 *   suppress:{comboHash}       실패 조합 재제안 억제 (01 5.4)
 *
 * 판정 게이트 — 확정 결과를 Redis에 남겨 스캔으로 불변식을 계수한다
 *   party:{partyId}            확정 파티
 *   party:{partyId}:members    requestId → 배정 포지션
 *   member:{requestId}         requestId → partyId   (INV-3)
 *   parties                    파티 id 전체 (스캔 진입점)
 * </pre>
 */
public final class RedisKeys {

    private RedisKeys() {}

    /** 제안·확정 전파 채널. 인스턴스 2대에 나뉜 참가자에게 동시에 닿게 한다(02 3장). */
    public static final String EVENT_CHANNEL = "qm:events";

    public static final String SEQ_REQUEST = "seq:request";
    public static final String SEQ_OFFER = "seq:offer";
    public static final String SEQ_PARTY = "seq:party";

    public static final String PARTY_INDEX = "parties";

    /** 응답을 기다리는 제안. score = 만료 시각(ms). 만료 스위퍼의 진입점이다. */
    public static final String PENDING_OFFERS = "offers:pending";

    /** 대기 명단. 큐 · 목표 인원이 다르면 애초에 후보가 아니므로(01 4.1) 명단을 나눈다. */
    public static String waitingQueue(Queue queue, int targetSize) {
        return "mq:" + queue.name() + ":" + targetSize;
    }

    public static String request(long requestId) {
        return "req:" + requestId;
    }

    public static String claim(long requestId) {
        return "claim:" + requestId;
    }

    public static String offer(long offerId) {
        return "offer:" + offerId;
    }

    public static String offerResponses(long offerId) {
        return "offer:" + offerId + ":responses";
    }

    public static String requestOffer(long requestId) {
        return "reqoffer:" + requestId;
    }

    public static String requestState(long requestId) {
        return "reqstate:" + requestId;
    }

    public static String suppress(String comboHash) {
        return "suppress:" + comboHash;
    }

    public static String party(long partyId) {
        return "party:" + partyId;
    }

    public static String partyMembers(long partyId) {
        return "party:" + partyId + ":members";
    }

    public static String member(long requestId) {
        return "member:" + requestId;
    }

    /**
     * 사용자가 만든 요청 id 집합. 02 3.4의 재연결 복구
     * ({@code GET /api/me/state})가 "현재 상태 전체"를 찾는 진입점이다.
     */
    public static String userRequests(long userId) {
        return "user:" + userId + ":requests";
    }

    /**
     * 사용자가 이미 점유한 시간 구간. score = 종료 시각, member = {@code "{partyId}|{시작 시각}"}.
     *
     * <p>02 2장의 {@code user_busy_interval} 테이블과 같은 역할이다. 거기서는
     * {@code EXCLUDE USING gist (user_id WITH =, during WITH &&)}가 INV-2를 막고,
     * 여기서는 {@code confirm_party.lua}가 쓰기 직전에 겹침을 검사해서 막는다.
     */
    public static String busy(long userId) {
        return "busy:" + userId;
    }
}

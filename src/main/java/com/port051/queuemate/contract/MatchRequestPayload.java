package com.port051.queuemate.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Set;

/**
 * {@code req:{requestId}} 에 담기는 주문 메모.
 *
 * <p><b>이 레코드의 필드 이름과 타입은 계약이다</b>
 * ({@code docs/05-realtime-matching-contract.md} 2절). 부하 스크립트가 이 모양에 꽂히므로
 * 이름을 바꾸거나 필드를 빼지 않는다. 서버 전용 값(최대 대기시간 등)은 여기에 넣지 않고
 * 설정으로 둔다 — 계약에 없는 필드를 얹으면 사람마다 스크립트가 갈린다.
 *
 * <p>판정에 필요한 조건이 전부 여기 들어 있어서 매칭 루프는 DB를 보지 않는다(05 "왜 Redis만 보나").
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MatchRequestPayload(
        long requestId,
        long userId,
        Queue queue,
        int targetSize,
        Purpose purpose,
        int playMinutes,
        VoiceMode voiceMode,
        Position primaryPosition,
        List<Position> subPositions,
        int tierOrder,
        int allowedTierMinOrder,
        int allowedTierMaxOrder,
        long requestedAt) {

    public MatchRequestPayload {
        subPositions = subPositions == null ? List.of() : List.copyOf(subPositions);
    }

    /** 이 요청이 맡을 수 있는 포지션 전체 — 주 + 부. 4.4 배정의 후보 집합이다. */
    public Set<Position> playablePositions() {
        java.util.EnumSet<Position> set = java.util.EnumSet.of(primaryPosition);
        set.addAll(subPositions);
        return set;
    }

    /**
     * 이 요청이 상대의 티어를 허용하는가. 01 4.1 — 서로의 허용 범위를 <b>양쪽 모두</b>
     * 만족해야 하므로 호출하는 쪽에서 두 방향을 다 본다.
     *
     * <p>판정에 쓰는 티어는 요청 생성 시점에 고정된 {@code tierOrder}다(01 3.5).
     * 캐시를 다시 읽지 않으므로 같은 입력에 항상 같은 결과가 나온다.
     */
    public boolean acceptsTier(int otherTierOrder) {
        return otherTierOrder >= allowedTierMinOrder && otherTierOrder <= allowedTierMaxOrder;
    }

    /** 즉시 매칭 파티의 종료 예정 시각 계산에 쓴다(01 3.1). INV-2의 전제. */
    public long endAtFrom(long startAtEpochMilli) {
        return startAtEpochMilli + playMinutes * 60_000L;
    }
}

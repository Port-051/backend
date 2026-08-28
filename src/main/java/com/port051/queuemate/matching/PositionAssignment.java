package com.port051.queuemate.matching;

import com.port051.queuemate.contract.Position;
import java.util.Map;

/**
 * 포지션 배정 결과. 01 4.4.
 *
 * @param byRequestId  요청 id → 배정된 포지션. 값은 서로 겹치지 않는다(INV-4)
 * @param primaryCount 주 포지션을 받은 사람 수. 많을수록 좋은 배정이다
 */
public record PositionAssignment(Map<Long, Position> byRequestId, int primaryCount) {
    public PositionAssignment {
        byRequestId = Map.copyOf(byRequestId);
    }

    public int size() {
        return byRequestId.size();
    }
}

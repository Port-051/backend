package com.port051.queuemate.matching.domain;

import java.util.List;

/**
 * 파티 참가자별 배정 포지션. 01-functional-spec-mvp 4.4.
 *
 * <p>{@code positions}의 {@code i}번째는 배정을 요청할 때 넘긴 참가자 목록의 {@code i}번째에
 * 대응한다. 요청 ID로 매핑하지 않는 이유는 참가자 목록을 들고 있는 쪽이 매칭 루프이고,
 * 그쪽이 순서를 그대로 안다면 짝을 짓는 데 그 이상이 필요하지 않기 때문이다.
 *
 * @param positions    참가자별 배정 포지션. 서로 겹치지 않는다
 * @param primaryCount 주 포지션을 받은 참가자 수. 4.4가 말하는 "좋은 배정"의 척도다
 */
public record PositionAssignment(List<Position> positions, int primaryCount) {

    public PositionAssignment {
        positions = List.copyOf(positions);
    }

    /** 참가자 수. */
    public int size() {
        return positions.size();
    }
}

package com.port051.queuemate.contract;

import java.util.List;
import java.util.Set;

/**
 * 큐 종류. 01 3.4 — 큐별로 선택할 수 있는 목표 파티 인원이 다르다.
 * 계약(05)의 {@code queue} 필드 값이므로 이름을 바꾸지 않는다.
 */
public enum Queue {
    /** 솔로·듀오 랭크. 게임 규칙상 1인 또는 2인만 함께 큐를 잡는다. */
    SOLO_DUO(Set.of(2), true),
    /** 자유 랭크. 1·2·3·5인만 가능하고 4인은 만들 수 없다. */
    FLEX(Set.of(2, 3, 5), false),
    /** 일반전. 1~5인. */
    NORMAL(Set.of(2, 3, 4, 5), false);

    private final Set<Integer> allowedSizes;
    private final boolean tierRestricted;

    Queue(Set<Integer> allowedSizes, boolean tierRestricted) {
        this.allowedSizes = allowedSizes;
        this.tierRestricted = tierRestricted;
    }

    public boolean allowsSize(int targetSize) {
        return allowedSizes.contains(targetSize);
    }

    /** 01 3.5 — 티어 제약은 솔로·듀오 랭크에만 적용한다. */
    public boolean isTierRestricted() {
        return tierRestricted;
    }

    public List<Integer> allowedSizes() {
        return allowedSizes.stream().sorted().toList();
    }
}

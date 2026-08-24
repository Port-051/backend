package com.port051.queuemate.matching.domain;

import java.util.Set;

/** 01 3.4 — 큐별 허용 목표 인원. 자유 랭크는 게임 규칙상 4인 파티를 만들 수 없다. */
public enum GameQueue {
    SOLO_DUO(Set.of(2), true),
    FLEX(Set.of(2, 3, 5), false),
    NORMAL(Set.of(2, 3, 4, 5), false);

    private final Set<Integer> allowedSizes;
    private final boolean gameTierRule;

    GameQueue(Set<Integer> allowedSizes, boolean gameTierRule) {
        this.allowedSizes = allowedSizes;
        this.gameTierRule = gameTierRule;
    }

    public boolean allowsSize(int targetSize) {
        return allowedSizes.contains(targetSize);
    }

    public Set<Integer> allowedSizes() {
        return allowedSizes;
    }

    /** 01 3.5 — 게임 규칙상 티어 제약은 솔로·듀오에만 적용된다. */
    public boolean gameTierRuleApplies() {
        return gameTierRule;
    }
}

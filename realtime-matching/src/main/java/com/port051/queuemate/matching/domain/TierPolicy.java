package com.port051.queuemate.matching.domain;

/**
 * 01 3.5 — 게임 규칙상 함께 플레이할 수 있는 티어 범위.
 * 허용 범위를 코드에 고정하지 않는 이유는 라이엇이 시즌마다 이 규칙을 조정하기 때문이다.
 * 구현은 설정 데이터로 주입한다({@code matching.tier-rule}).
 */
public interface TierPolicy {

    /** 두 요청이 게임 규칙상 함께 큐를 잡을 수 있는가. */
    boolean playableTogether(GameQueue queue, Integer aTierOrder, Integer bTierOrder);

    /** 내 티어 기준으로 선택할 수 있는 허용 범위의 폭 (디비전 단위). */
    int allowedSpread(GameQueue queue, Integer tierOrder);

    /** 01 3.6 · 3.7 — 솔로·듀오 요청을 만들 수 있는 티어인가. */
    boolean eligibleForSoloDuo(Integer tierOrder);
}

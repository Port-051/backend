package com.port051.queuemate.contract;

/**
 * 플레이 목적. 01 1.4 · 3.8 — 매칭 조건이며 전부 일치해야 한다(4.1).
 * 계약(05)의 {@code purpose} 값이다.
 */
public enum Purpose {
    /** 가볍게 즐기기 */
    CASUAL,
    /** 승리·랭크 상승 */
    RANK_UP,
    /** 초보자 학습 */
    LEARNING,
    /** 숙련자 중심 플레이 */
    SERIOUS
}

package com.port051.queuemate.contract;

/**
 * 포지션. 01 2.4 — 탑·정글·미드·바텀·서포터.
 * 계약(05)의 {@code primaryPosition} · {@code subPositions} 값이다.
 */
public enum Position {
    TOP, JUNGLE, MID, BOTTOM, SUPPORT;

    public static final int COUNT = 5;
}

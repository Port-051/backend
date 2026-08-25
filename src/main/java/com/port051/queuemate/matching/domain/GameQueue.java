package com.port051.queuemate.matching.domain;

/**
 * 큐 종류. 01-functional-spec-mvp 3.4.
 *
 * <p>타입 이름이 {@code Queue}가 아닌 이유는 {@link java.util.Queue}와 충돌하기 때문이다.
 * 계약이 고정한 것은 JSON 필드 이름 {@code queue}이지 자바 타입 이름이 아니다.
 */
public enum GameQueue {

    /** 솔로·듀오 랭크. 목표 인원 2만 가능하고, 3.5의 티어 제약을 받는다. */
    SOLO_DUO,

    /** 자유 랭크. 목표 인원 2·3·5. 게임 규칙상 4인 파티를 만들 수 없다. */
    FLEX,

    /** 일반. 목표 인원 2·3·4·5. 티어 제약이 없다. */
    NORMAL
}

package com.port051.queuemate.matching.domain;

/**
 * 포지션. 01-functional-spec-mvp 2.4 · 4.4.
 *
 * <p>{@code 상관없음}에 해당하는 값은 두지 않는다. 2.4가 그것을
 * "다섯 포지션 전부를 부 포지션으로 취급한다"로 정의하므로 요청을 만드는 시점에
 * 다섯 값으로 펼쳐지고, 매칭에는 펼쳐진 결과만 들어온다.
 */
public enum Position {

    TOP,
    JUNGLE,
    MID,
    BOTTOM,
    SUPPORT
}

package com.port051.queuemate.matching.domain;

/**
 * 음성채팅 조건. 01-functional-spec-mvp 1.4 · 4.2.
 *
 * <p>값이 다르다고 곧바로 제외하지 않는다. 호환 판정은 4.2의 표를 따른다.
 */
public enum VoiceMode {

    /** 필수. 파티에 {@link #NOT_USED}인 사람을 넣지 않는다. */
    REQUIRED,

    /** 가능. 셋 중 누구와도 호환된다. */
    POSSIBLE,

    /** 사용하지 않음. {@link #REQUIRED}인 사람과 호환되지 않는다. */
    NOT_USED
}

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
    NOT_USED;

    /**
     * 두 사람의 음성채팅 조건이 호환되는지. 4.2의 표를 그대로 옮긴 것이다.
     *
     * <p>표가 대각선 대칭이라 {@code a.isCompatibleWith(b)}와 {@code b.isCompatibleWith(a)}는 같다.
     * 아홉 칸 중 막히는 것은 {@link #REQUIRED}와 {@link #NOT_USED}가 만나는 두 칸뿐이므로
     * 표를 자료구조로 들지 않고 그 조합만 걸러낸다.
     *
     * <p>파티 전체 규칙(참가자 중 한 명이라도 {@code 필수}면 음성 파티가 된다)은 여기서 다루지 않는다.
     * 짝 판정을 모두 통과한 조합은 파티 전체로 봐도 이 규칙을 만족하므로,
     * 파티의 음성 사용 여부는 성립 이후에 정하면 된다.
     */
    public boolean isCompatibleWith(VoiceMode other) {
        return !(this == REQUIRED && other == NOT_USED)
                && !(this == NOT_USED && other == REQUIRED);
    }
}

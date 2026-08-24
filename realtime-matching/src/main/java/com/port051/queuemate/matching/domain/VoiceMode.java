package com.port051.queuemate.matching.domain;

/**
 * 음성채팅 조건 — 01 4.2.
 *
 * <p>세 값이 있고, <b>값이 다르다고 곧바로 제외하지 않는다</b>는 것이 이 타입의 존재 이유다.
 * 서로 다른 값끼리도 대부분 붙고, 어긋나는 조합은 하나뿐이다.
 */
public enum VoiceMode {

    /** 필수 — 음성으로 대화할 수 있어야 한다. */
    REQUIRED,

    /** 가능 — 상대가 원하면 맞춘다. */
    POSSIBLE,

    /** 사용하지 않음 — 음성을 쓰지 않는다. */
    NONE;

    /**
     * 두 사람이 같은 파티에 들어갈 수 있는가.
     *
     * <p>01 4.2의 3×3 표를 규칙 둘로 접은 것이다. 한쪽이라도 {@code POSSIBLE}이면
     * 상대가 무엇이든 맞고, 그렇지 않으면 같은 값일 때만 맞는다.
     * 결국 어긋나는 조합은 {@code REQUIRED}와 {@code NONE} 하나뿐이다.
     */
    public boolean compatibleWith(VoiceMode other) {
        if (this == POSSIBLE || other == POSSIBLE) {
            return true;
        }
        return this == other;
    }
}

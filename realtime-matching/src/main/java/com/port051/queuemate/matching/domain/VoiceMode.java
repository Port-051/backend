package com.port051.queuemate.matching.domain;

/** 01 1.4 · 4.2 — 필수 / 가능 / 사용하지 않음. */
public enum VoiceMode {
    REQUIRED, POSSIBLE, NONE;

    /**
     * 01 4.2의 호환 표. 값이 다르다고 곧바로 제외하지 않는다.
     * 파티 전체 규칙(한 명이라도 REQUIRED면 NONE을 넣지 않는다)은
     * 쌍 단위 판정을 전원에게 적용하면 그대로 성립한다.
     */
    public boolean compatibleWith(VoiceMode other) {
        if (this == POSSIBLE || other == POSSIBLE) {
            return true;
        }
        return this == other;
    }

    /** 확정 파티가 음성 파티인지 (01 4.2 · party.voice_party). */
    public static boolean voiceParty(java.util.Collection<VoiceMode> modes) {
        return modes.stream().anyMatch(m -> m == REQUIRED);
    }
}

package com.port051.queuemate.contract;

/**
 * 음성채팅 조건. 01 1.4 · 4.2.
 * 계약(05)의 {@code voiceMode} 값이다.
 */
public enum VoiceMode {
    /** 필수 */
    REQUIRED,
    /** 가능 */
    POSSIBLE,
    /** 사용하지 않음 */
    NONE
}

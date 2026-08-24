package com.port051.queuemate.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VoiceModeTest {

    @Test
    @DisplayName("01 4.2 호환 표를 그대로 옮긴다")
    void compatibilityTable() {
        assertThat(VoiceMode.REQUIRED.compatibleWith(VoiceMode.REQUIRED)).isTrue();
        assertThat(VoiceMode.REQUIRED.compatibleWith(VoiceMode.POSSIBLE)).isTrue();
        assertThat(VoiceMode.REQUIRED.compatibleWith(VoiceMode.NONE)).isFalse();
        assertThat(VoiceMode.POSSIBLE.compatibleWith(VoiceMode.NONE)).isTrue();
        assertThat(VoiceMode.NONE.compatibleWith(VoiceMode.NONE)).isTrue();
        assertThat(VoiceMode.NONE.compatibleWith(VoiceMode.REQUIRED)).isFalse();
    }

    @Test
    @DisplayName("한 명이라도 필수면 음성 파티다")
    void voiceParty() {
        assertThat(VoiceMode.voiceParty(List.of(VoiceMode.POSSIBLE, VoiceMode.REQUIRED))).isTrue();
        assertThat(VoiceMode.voiceParty(List.of(VoiceMode.POSSIBLE, VoiceMode.NONE))).isFalse();
    }
}

package com.port051.queuemate.matching.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 01-functional-spec-mvp 4.2의 호환표를 아홉 칸 전부 확인한다. */
class VoiceModeTest {

    @ParameterizedTest(name = "{0} × {1} = {2}")
    @DisplayName("4.2 호환표 아홉 칸")
    @CsvSource({
            "REQUIRED, REQUIRED, true",
            "REQUIRED, POSSIBLE, true",
            "REQUIRED, NOT_USED, false",

            "POSSIBLE, REQUIRED, true",
            "POSSIBLE, POSSIBLE, true",
            "POSSIBLE, NOT_USED, true",

            "NOT_USED, REQUIRED, false",
            "NOT_USED, POSSIBLE, true",
            "NOT_USED, NOT_USED, true",
    })
    void matchesTheContractTable(VoiceMode mine, VoiceMode theirs, boolean compatible) {
        assertThat(mine.isCompatibleWith(theirs)).isEqualTo(compatible);
    }

    @ParameterizedTest
    @DisplayName("호환 판정은 순서를 바꿔도 같다")
    @EnumSource(VoiceMode.class)
    void isSymmetric(VoiceMode mine) {
        for (VoiceMode theirs : VoiceMode.values()) {
            assertThat(mine.isCompatibleWith(theirs))
                    .as("%s × %s", mine, theirs)
                    .isEqualTo(theirs.isCompatibleWith(mine));
        }
    }

    @Test
    @DisplayName("가능은 셋 중 누구와도 호환된다")
    void possibleAcceptsEveryone() {
        for (VoiceMode theirs : VoiceMode.values()) {
            assertThat(VoiceMode.POSSIBLE.isCompatibleWith(theirs)).isTrue();
        }
    }
}

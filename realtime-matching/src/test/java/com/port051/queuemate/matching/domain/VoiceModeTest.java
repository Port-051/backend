package com.port051.queuemate.matching.domain;

import static com.port051.queuemate.matching.domain.VoiceMode.NONE;
import static com.port051.queuemate.matching.domain.VoiceMode.POSSIBLE;
import static com.port051.queuemate.matching.domain.VoiceMode.REQUIRED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VoiceModeTest {

    /**
     * 01 4.2의 표를 그대로 옮긴다. 행이 나, 열이 상대다.
     *
     * <pre>
     *              필수   가능   사용안함
     *   필수         O     O      X
     *   가능         O     O      O
     *   사용안함      X     O      O
     * </pre>
     *
     * 구현은 규칙 두 줄이지만 테스트는 표 그대로 둔다.
     * 명세의 표가 바뀌면 규칙보다 이 배열이 먼저 깨져야 하기 때문이다.
     */
    private static final VoiceMode[] ORDER = {REQUIRED, POSSIBLE, NONE};

    private static final boolean[][] TABLE = {
            {true, true, false},
            {true, true, true},
            {false, true, true},
    };

    @Test
    @DisplayName("01 4.2 호환 표의 아홉 칸이 모두 맞는다")
    void matchesSpecTable() {
        for (int row = 0; row < ORDER.length; row++) {
            for (int column = 0; column < ORDER.length; column++) {
                VoiceMode mine = ORDER[row];
                VoiceMode theirs = ORDER[column];

                // 세 번째 인자가 없으면 아홉 칸 중 어디가 틀렸는지 실패 메시지에 남지 않는다.
                assertEquals(TABLE[row][column], mine.compatibleWith(theirs),
                        () -> "%s ↔ %s".formatted(mine, theirs));
            }
        }
    }

    @Test
    @DisplayName("호환 판정은 순서를 바꿔도 같다")
    void symmetric() {
        // 명세의 표가 대칭이므로 구현도 대칭이어야 한다는 판단이다.
        // 비대칭 규칙이 생기면 이 테스트를 먼저 지워야 한다.
        for (VoiceMode mine : VoiceMode.values()) {
            for (VoiceMode theirs : VoiceMode.values()) {
                assertEquals(mine.compatibleWith(theirs), theirs.compatibleWith(mine),
                        () -> "%s ↔ %s".formatted(mine, theirs));
            }
        }
    }
}

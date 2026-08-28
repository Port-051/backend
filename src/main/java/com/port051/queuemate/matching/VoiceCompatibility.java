package com.port051.queuemate.matching;

import com.port051.queuemate.contract.VoiceMode;
import java.util.Collection;

/**
 * 음성채팅 호환. 01 4.2.
 *
 * <p>값이 다르다고 곧바로 제외하지 않는다는 것이 이 절의 핵심이다.
 * {@code POSSIBLE}은 양쪽 모두와 붙고, {@code REQUIRED}와 {@code NONE}만 서로 막힌다.
 *
 * <pre>
 *              필수  가능  사용안함
 *   필수        O     O      X
 *   가능        O     O      O
 *   사용안함    X     O      O
 * </pre>
 */
public final class VoiceCompatibility {

    private VoiceCompatibility() {}

    /** 두 사람 사이의 호환 여부. */
    public static boolean compatible(VoiceMode a, VoiceMode b) {
        return !(a == VoiceMode.REQUIRED && b == VoiceMode.NONE)
                && !(a == VoiceMode.NONE && b == VoiceMode.REQUIRED);
    }

    /**
     * 파티 전체 규칙. 01 4.2 —
     * 한 명이라도 {@code REQUIRED}면 음성 파티가 되고 {@code NONE}인 사람을 넣지 않는다.
     *
     * <p>쌍 단위 검사를 모든 조합에 도는 것과 결과가 같지만 O(n)으로 끝난다.
     * {@code REQUIRED}와 {@code NONE}이 한 파티에 같이 있으면 안 된다는 것이 유일한 제약이기 때문이다.
     */
    public static boolean partyCompatible(Collection<VoiceMode> modes) {
        boolean required = false;
        boolean none = false;
        for (VoiceMode mode : modes) {
            if (mode == VoiceMode.REQUIRED) required = true;
            if (mode == VoiceMode.NONE) none = true;
        }
        return !(required && none);
    }

    /** 확정된 파티가 음성 파티인가. 01 4.2 — 한 명이라도 {@code REQUIRED}면 그렇다. */
    public static boolean isVoiceParty(Collection<VoiceMode> modes) {
        return modes.stream().anyMatch(m -> m == VoiceMode.REQUIRED);
    }
}

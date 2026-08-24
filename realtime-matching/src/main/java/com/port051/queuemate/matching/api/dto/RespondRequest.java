package com.port051.queuemate.matching.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 01 5.2 — 수락·거절. {@code keepSearching}은 5.3의 "계속 찾기 / 매칭 종료"다.
 * 거절한 사람에게만 의미가 있고, 수락 경로에서는 무시된다.
 */
public record RespondRequest(@NotNull Long requestId, Boolean keepSearching) {
    public boolean keepSearchingOrDefault() {
        return keepSearching == null || keepSearching;
    }
}

package com.port051.queuemate.matching.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;

/** 성립한 파티 후보. 아직 제안(offer)이 아니다 — 좌석 선점에 성공해야 제안이 된다. */
public record ComposedParty(List<MatchRequestView> members, PositionAssignment positions) {

    /** 01 5.4 — 조합은 참가 요청 식별자의 집합으로 정의한다. */
    public SequencedSet<Long> combo() {
        SequencedSet<Long> ids = new LinkedHashSet<>();
        members.stream().map(MatchRequestView::requestId).sorted().forEach(ids::add);
        return ids;
    }

    public boolean voiceParty() {
        return VoiceMode.voiceParty(members.stream().map(MatchRequestView::voiceMode).toList());
    }
}

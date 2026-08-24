package com.port051.queuemate.matching.offer;

import com.port051.queuemate.matching.domain.GameQueue;
import com.port051.queuemate.matching.domain.Position;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 01 5.1 — 즉시 매칭 제안. 참가자 전원에게 같은 시점에 보낸다.
 *
 * <p>{@code expiresAt}은 <b>제안 생성 시각 기준</b>이다(02 3.1). 클라이언트 수신 시각으로
 * 계산하면 먼저 받은 사람이 유리해진다.
 */
public record Offer(
        long offerId,
        GameQueue queue,
        int targetSize,
        long createdAt,
        long expiresAt,
        List<Participant> participants
) {

    public record Participant(long requestId, long userId, Position position, int playMinutes) {
    }

    public List<Long> requestIds() {
        return participants.stream().map(Participant::requestId).toList();
    }

    public List<Long> userIds() {
        return participants.stream().map(Participant::userId).toList();
    }

    public Optional<Participant> participantOf(long requestId) {
        return participants.stream().filter(p -> p.requestId() == requestId).findFirst();
    }

    /** 01 5.1 — 배정 포지션은 제안 단계에서 공개한다. Riot ID·Discord는 확정 전까지 공개하지 않는다. */
    public Map<String, String> positionsByUserId() {
        Map<String, String> positions = new LinkedHashMap<>();
        participants.forEach(p -> positions.put(String.valueOf(p.userId()), p.position().name()));
        return positions;
    }

    /** 01 3.1 — 종료 예정 시각은 확정 시각 + 예상 플레이시간. 파티 점유 구간은 가장 긴 쪽을 덮는다. */
    public int partyMinutes() {
        return participants.stream().mapToInt(Participant::playMinutes).max().orElse(0);
    }
}

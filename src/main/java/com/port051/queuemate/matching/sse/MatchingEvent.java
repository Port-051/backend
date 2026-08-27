package com.port051.queuemate.matching.sse;

import com.port051.queuemate.matching.domain.GameQueue;
import com.port051.queuemate.matching.domain.MatchRequest;
import com.port051.queuemate.matching.domain.Party;
import com.port051.queuemate.matching.domain.Position;
import com.port051.queuemate.matching.domain.Purpose;

import java.util.ArrayList;
import java.util.List;

/**
 * 사용자 한 명에게 보낼 소식. 05-realtime-matching-contract 3장 4단계.
 *
 * <p>파티 하나가 성립하면 참가자 수만큼 만들어진다. 받는 사람마다 배정 포지션이 다르므로
 * 파티를 통째로 보내지 않고 <b>각자에게 필요한 것만</b> 담는다.
 *
 * <p>상대방의 Riot ID나 Discord 계정은 담지 않는다. 5.1이 확정 전까지 공개하지 말라고 정했고,
 * 애초에 이 단계에는 그 정보가 없다.
 *
 * @param type      무슨 일인지
 * @param userId    받을 사람
 * @param requestId 어느 요청에 대한 소식인지
 * @param queue     큐. 만료면 {@code null}일 수 있다
 * @param position  배정받은 포지션. 확정일 때만 있다
 */
public record MatchingEvent(
        Type type,
        long userId,
        long requestId,
        GameQueue queue,
        Integer targetSize,
        Purpose purpose,
        Position position,
        Boolean voiceParty
) {

    public enum Type {
        /** 파티가 성립했다. */
        PARTY_CONFIRMED,
        /** 최대 대기시간이 지나 요청이 종료됐다. 01-functional-spec-mvp 10.2. */
        REQUEST_EXPIRED
    }

    /** 성립한 파티를 참가자별 소식으로 펼친다. */
    public static List<MatchingEvent> confirmed(Party party) {
        List<MatchingEvent> events = new ArrayList<>(party.size());
        for (int i = 0; i < party.size(); i++) {
            MatchRequest member = party.members().get(i);
            events.add(new MatchingEvent(
                    Type.PARTY_CONFIRMED,
                    member.userId(), member.requestId(),
                    party.queue(), party.targetSize(), party.purpose(),
                    party.positionOf(i), party.voiceParty()));
        }
        return events;
    }

    /**
     * 만료된 요청의 소식.
     *
     * <p>10.2는 4.5의 실패 사유를 함께 제공하라고 하지만 여기서는 종료 사실만 알린다.
     * 사유 계산은 조건을 하나씩 빼가며 후보 수를 다시 세는 일이라 별개의 작업이다.
     */
    public static MatchingEvent expired(MatchRequest request) {
        return new MatchingEvent(
                Type.REQUEST_EXPIRED,
                request.userId(), request.requestId(),
                request.queue(), request.targetSize(), request.purpose(),
                null, null);
    }
}

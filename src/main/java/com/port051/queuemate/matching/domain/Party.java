package com.port051.queuemate.matching.domain;

import java.util.List;

/**
 * 성립한 파티. 01-functional-spec-mvp 4.3 · 4.4.
 *
 * <p>{@code members}의 {@code i}번째 참가자가 받은 자리는 {@code assignment}의 {@code i}번째다.
 * 참가자 순서는 채택된 순서이며, 4.3의 전순서를 따르므로 맨 앞이 기준 요청이다.
 *
 * <p>확정 이후의 일 — 제안 전송, Discord 전용방, 요청 상태 전이 — 은 여기서 하지 않는다.
 * 그것들은 각각 별개의 기능이고, 매칭이 책임지는 범위는 파티가 성립하는 지점까지다.
 *
 * @param members    참가자. 첫 번째가 기준 요청이다
 * @param assignment 참가자별 배정 포지션. {@code members}와 순서가 같다
 */
public record Party(List<MatchRequest> members, PositionAssignment assignment) {

    public Party {
        members = List.copyOf(members);
        if (members.size() != assignment.size()) {
            throw new IllegalArgumentException(
                    "참가자 수와 배정 수가 다르다: " + members.size() + " != " + assignment.size());
        }
    }

    /** 참가자 수. 파티가 성립했다면 {@link #targetSize()}와 같다. */
    public int size() {
        return members.size();
    }

    /** 큐. 4.1이 큐가 다른 요청을 후보에서 제외하므로 참가자 전원이 같다. */
    public GameQueue queue() {
        return members.getFirst().queue();
    }

    /** 목표 인원. 4.1이 목표 인원이 다른 요청을 제외하므로 참가자 전원이 같다. */
    public int targetSize() {
        return members.getFirst().targetSize();
    }

    /** 합의된 목적. 4.1이 목적이 다른 요청을 제외하므로 참가자 전원이 같다. */
    public Purpose purpose() {
        return members.getFirst().purpose();
    }

    /** {@code i}번째 참가자가 받은 포지션. */
    public Position positionOf(int index) {
        return assignment.positions().get(index);
    }

    /**
     * 음성 파티인지. 01-functional-spec-mvp 4.2.
     *
     * <p>"참가자 중 한 명이라도 {@code 필수}이면 그 파티는 음성 파티가 되며,
     * 참가자가 전부 {@code 가능} 또는 {@code 사용하지 않음}이면 비음성 파티가 된다."
     *
     * <p>{@code 필수}인 사람과 {@code 사용하지 않음}인 사람이 한 파티에 있을 수 없다는 것은
     * 4.1을 통과한 시점에 이미 보장돼 있다. 여기서는 어느 쪽인지만 가른다.
     */
    public boolean voiceParty() {
        return members.stream().anyMatch(member -> member.voiceMode() == VoiceMode.REQUIRED);
    }
}

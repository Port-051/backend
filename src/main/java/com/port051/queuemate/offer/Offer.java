package com.port051.queuemate.offer;

import com.port051.queuemate.contract.Position;
import com.port051.queuemate.contract.Queue;
import java.util.List;
import java.util.Map;

/**
 * 제안 한 건. 01 5.1.
 *
 * <p>{@code expiresAt}은 <b>제안 생성 시각 기준</b>으로 계산한다(02 3.1).
 * 클라이언트 수신 시각으로 잡으면 인스턴스가 가까워 먼저 받은 사람이 더 오래 고민할 수 있게 되고,
 * 그건 "먼저 받은 사람이 유리해지면 안 된다"를 깬다.
 *
 * @param members requestId → 배정 예정 포지션. 확정 전이므로 아직 파티가 아니다
 * @param userIds requestId → userId. 이벤트를 보낼 대상을 찾는 데 쓴다
 */
public record Offer(
        long offerId,
        Queue queue,
        int targetSize,
        long createdAt,
        long expiresAt,
        OfferStatus status,
        String comboHash,
        Map<Long, Position> members,
        Map<Long, Long> userIds) {

    public List<Long> requestIds() {
        return members.keySet().stream().sorted().toList();
    }

    public long remainingMillis(long now) {
        return Math.max(0, expiresAt - now);
    }
}

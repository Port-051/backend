package com.port051.queuemate.matching;

import com.port051.queuemate.contract.MatchRequestPayload;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 후보 제외. 01 4.1.
 *
 * <p>명세의 열 가지 제외 사유 중 이 클래스가 보는 것은 다섯이다. 나머지는 다른 데서 이미 걸린다.
 *
 * <table>
 *   <tr><th>제외 사유 (01 4.1)</th><th>어디서 걸리나</th></tr>
 *   <tr><td>큐가 다르다</td><td>대기 명단이 {@code mq:{queue}:{targetSize}}로 갈려 있다</td></tr>
 *   <tr><td>목표 파티 인원이 다르다</td><td>같은 이유</td></tr>
 *   <tr><td>플레이 가능한 시간이 겹치지 않는다</td><td>즉시 매칭은 전부 "지금"이라 항상 겹친다. 예약(01 3.2)은 스파이크 범위 밖</td></tr>
 *   <tr><td>서로의 허용 티어 범위</td><td><b>여기</b> — 양방향으로 본다</td></tr>
 *   <tr><td>게임 규칙상 함께 못 하는 티어</td><td><b>여기</b> — {@link TierRule}</td></tr>
 *   <tr><td>음성채팅 비호환</td><td><b>여기</b> — {@link VoiceCompatibility}</td></tr>
 *   <tr><td>플레이 목적이 다르다</td><td><b>여기</b></td></tr>
 *   <tr><td>포지션 배정 불가</td><td><b>여기</b> — {@link PositionAssigner}</td></tr>
 *   <tr><td>이미 시간이 겹치는 확정 파티에 속해 있다</td><td>확정 시 명단에서 지워지고 {@code claim}이 붙는다</td></tr>
 *   <tr><td>차단 쌍</td><td>스파이크 범위 밖 — 01 7.7이고 계약 JSON에 필드가 없다</td></tr>
 * </table>
 *
 * <p><b>티어를 두 갈래로 보는 이유.</b> 01 4.1은 "서로의 허용 티어 범위"와 "게임 규칙상 함께
 * 플레이할 수 없는 티어"를 <b>다른 항목으로</b> 적었다. 앞은 사용자가 고른 범위라 큐와 무관하게
 * 양방향으로 만족해야 하고(01 3.8이 조건 항목에 큐 구분 없이 올려두었다),
 * 뒤는 라이엇의 규칙이라 솔로·듀오 랭크에만 걸린다(01 3.5 마지막 줄).
 */
@Component
public class CandidateFilter {

    private final TierRule tierRule;

    public CandidateFilter(TierRule tierRule) {
        this.tierRule = tierRule;
    }

    /**
     * 두 요청이 같은 파티에 들어갈 수 있는가. 포지션은 보지 않는다 —
     * 포지션은 쌍이 아니라 파티 전체에서 판정해야 하기 때문이다(01 4.4).
     */
    public boolean pairCompatible(MatchRequestPayload a, MatchRequestPayload b) {
        if (a.requestId() == b.requestId()) return false;
        if (a.userId() == b.userId()) return false; // 한 사람이 자기 자신과 묶이지 않는다
        if (a.queue() != b.queue()) return false;
        if (a.targetSize() != b.targetSize()) return false;
        if (a.purpose() != b.purpose()) return false;
        if (!a.acceptsTier(b.tierOrder()) || !b.acceptsTier(a.tierOrder())) return false;
        if (!tierRule.canQueueTogether(a.queue().isTierRestricted(), a.tierOrder(), b.tierOrder())) {
            return false;
        }
        return VoiceCompatibility.compatible(a.voiceMode(), b.voiceMode());
    }

    /**
     * 후보 하나를 지금까지 모은 파티에 얹을 수 있는가.
     *
     * <p>기존 구성원 <b>전원</b>과 쌍으로 맞아야 하고, 얹은 뒤에도 포지션이 겹치지 않게
     * 나눠줄 수 있어야 한다. 포지션은 마지막에 한 번만 보면 되지만, 여기서 미리 끊지 않으면
     * 정원을 다 채운 뒤에야 불가능을 알게 되어 탐색이 낭비된다.
     */
    public boolean canJoin(List<MatchRequestPayload> party, MatchRequestPayload candidate) {
        for (MatchRequestPayload member : party) {
            if (!pairCompatible(member, candidate)) return false;
        }
        List<MatchRequestPayload> extended = new ArrayList<>(party);
        extended.add(candidate);
        return PositionAssigner.feasible(extended);
    }
}

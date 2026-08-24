package com.port051.queuemate.matching.domain;

/**
 * 01 4.1 — 후보 제외. 조건은 전부 필수이며 유사도 점수를 계산하지 않는다(01 3.8 · 4.3).
 *
 * <p>이 스파이크에서 판정하지 않는 두 조건이 있다.
 * <ul>
 *   <li>차단·재회(01 7.7) — {@code user_relation}이 DB에 있다. 실시간 매칭은 DB를 보지 않으므로
 *       Core API가 메모에 실어 주는 형태로 바꿔야 한다. 스파이크 범위 밖.</li>
 *   <li>시간 겹침(01 4.1) — 즉시 매칭은 확정 시각이 곧 시작 시각이라 대기 중인 요청끼리는
 *       항상 겹친다. 확정 파티와의 겹침(INV-2)은 Redis의 사용자 단위 claim으로 근사한다.</li>
 * </ul>
 */
public final class CandidateFilter {

    private final TierPolicy tierPolicy;

    public CandidateFilter(TierPolicy tierPolicy) {
        this.tierPolicy = tierPolicy;
    }

    public boolean compatible(MatchRequestView a, MatchRequestView b) {
        if (a.requestId() == b.requestId() || a.userId() == b.userId()) {
            return false;
        }
        if (a.queue() != b.queue() || a.targetSize() != b.targetSize()) {
            return false;
        }
        if (a.purpose() != b.purpose()) {
            return false;
        }
        if (!a.voiceMode().compatibleWith(b.voiceMode())) {
            return false;
        }
        if (!satisfiesAllowedRange(a, b) || !satisfiesAllowedRange(b, a)) {
            return false;
        }
        return tierPolicy.playableTogether(a.queue(), a.tierOrder(), b.tierOrder());
    }

    /** 01 4.1 — 서로의 허용 티어 범위를 양쪽 모두 만족해야 한다. */
    private static boolean satisfiesAllowedRange(MatchRequestView viewer, MatchRequestView other) {
        if (other.tierOrder() == null) {
            return true;   // 언랭크는 티어 조건의 대상이 아니다 (01 3.7 — 자유·일반만 가능)
        }
        if (viewer.allowedTierMinOrder() != null && other.tierOrder() < viewer.allowedTierMinOrder()) {
            return false;
        }
        return viewer.allowedTierMaxOrder() == null || other.tierOrder() <= viewer.allowedTierMaxOrder();
    }
}

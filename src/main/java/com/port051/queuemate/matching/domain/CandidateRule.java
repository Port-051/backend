package com.port051.queuemate.matching.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 4.1의 후보 제외 사유 중 <b>요청 두 개만으로 판정할 수 있는 것</b>들. 01-functional-spec-mvp 4.1 · 4.2.
 *
 * <p>4.1은 제외 사유를 열 개 열거하지만 전부가 여기 오지는 않는다. 빠진 것과 이유는 다음과 같다.
 *
 * <ul>
 *   <li><b>차단 쌍</b>(7.7)과 <b>시간이 겹치는 확정 파티</b> — 요청 바깥의 상태를 봐야 한다.
 *       매칭 루프가 별도 입력으로 받아 거른다.
 *   <li><b>포지션 배정</b>(4.4) — 짝이 아니라 파티 전체를 놓고 봐야 판정된다.
 *   <li><b>플레이 가능 시간</b> — 계약(05 2장)이 요청에 담는 것은 시작·종료 시각이 아니라
 *       플레이 길이 {@code playMinutes} 하나뿐이다. 즉시 매칭은 모두 지금 시작하므로
 *       겹치는지 볼 구간 자체가 없다.
 *   <li><b>게임 규칙상 함께 플레이할 수 없는 티어</b>(3.5) — 허용 범위는 요청을 만드는 시점에
 *       이미 규칙 안으로 좁혀져 있다({@link MatchRequest}). 매칭은 두 범위가 서로를
 *       만족하는지만 본다.
 * </ul>
 *
 * <p>통과 여부를 boolean 하나로 합치지 않고 사유를 낱개로 두는 이유는 4.5 때문이다.
 * "조건을 하나씩 빼가며 각 조건이 후보를 얼마나 줄였는지" 세려면 조건이 각각 지목 가능해야 한다.
 *
 * <p>모든 규칙은 인자 순서를 바꿔도 결과가 같다. 4.1이 제외를 쌍 단위로 정의하기 때문이다.
 */
public enum CandidateRule {

    /** 큐가 같아야 한다. */
    QUEUE("큐") {
        @Override
        public boolean isSatisfiedBy(MatchRequest a, MatchRequest b) {
            return a.queue() == b.queue();
        }
    },

    /** 목표 파티 인원이 같아야 한다. */
    TARGET_SIZE("목표 인원") {
        @Override
        public boolean isSatisfiedBy(MatchRequest a, MatchRequest b) {
            return a.targetSize() == b.targetSize();
        }
    },

    /** 플레이 목적이 정확히 같아야 한다. {@link VoiceMode}와 달리 호환표가 없다. */
    PURPOSE("플레이 목적") {
        @Override
        public boolean isSatisfiedBy(MatchRequest a, MatchRequest b) {
            return a.purpose() == b.purpose();
        }
    },

    /** 음성채팅 조건이 4.2의 표에서 호환이어야 한다. */
    VOICE_MODE("음성채팅") {
        @Override
        public boolean isSatisfiedBy(MatchRequest a, MatchRequest b) {
            return a.voiceMode().isCompatibleWith(b.voiceMode());
        }
    },

    /**
     * 서로의 허용 티어 범위를 <b>양쪽 모두</b> 만족해야 한다.
     *
     * <p>한쪽만 만족하는 경우가 제외 대상이다. 상위 티어가 하위와 함께할 의사가 있어도
     * 하위가 상위를 범위에 넣지 않았다면 후보가 아니다.
     */
    TIER_RANGE("허용 티어 범위") {
        @Override
        public boolean isSatisfiedBy(MatchRequest a, MatchRequest b) {
            return accepts(a, b) && accepts(b, a);
        }
    };

    /** 매칭 루프를 도는 동안 {@code values()}가 매번 배열을 새로 만들지 않도록 한 번만 복사해 둔다. */
    private static final CandidateRule[] ALL = values();

    private final String label;

    CandidateRule(String label) {
        this.label = label;
    }

    /** 4.5가 실패 사유를 보여줄 때 쓸 조건 이름. */
    public String label() {
        return label;
    }

    /** 두 요청이 이 조건을 만족하는지. */
    public abstract boolean isSatisfiedBy(MatchRequest a, MatchRequest b);

    /** {@code target}의 티어가 {@code judge}가 허용한 범위 안에 있는지. 경계는 포함한다. */
    private static boolean accepts(MatchRequest judge, MatchRequest target) {
        return judge.allowedTierMinOrder() <= target.tierOrder()
                && target.tierOrder() <= judge.allowedTierMaxOrder();
    }

    /**
     * 두 요청이 여기 있는 조건을 전부 만족하는지.
     *
     * <p>4.1의 나머지 사유와 4.4의 포지션 배정이 남아 있으므로, 이것이 참이라고 해서
     * 곧바로 같은 파티가 되는 것은 아니다.
     */
    public static boolean allSatisfied(MatchRequest a, MatchRequest b) {
        for (CandidateRule rule : ALL) {
            if (!rule.isSatisfiedBy(a, b)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 두 요청이 어긋난 조건 전부. 후보이면 빈 목록이다.
     *
     * <p>4.5의 실패 사유 집계에 쓴다. {@link #allSatisfied}와 달리 첫 실패에서 멈추지 않는다.
     * 순서는 이 enum의 선언 순서이므로 같은 입력에는 항상 같은 목록이 나온다(4.3).
     */
    public static List<CandidateRule> unsatisfiedBy(MatchRequest a, MatchRequest b) {
        List<CandidateRule> unsatisfied = new ArrayList<>();
        for (CandidateRule rule : ALL) {
            if (!rule.isSatisfiedBy(a, b)) {
                unsatisfied.add(rule);
            }
        }
        return List.copyOf(unsatisfied);
    }
}

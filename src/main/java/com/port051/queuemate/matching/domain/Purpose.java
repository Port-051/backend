package com.port051.queuemate.matching.domain;

/**
 * 플레이 목적. 01-functional-spec-mvp 1.4.
 *
 * <p>4.1이 "플레이 목적이 다르다"를 후보 제외 사유로 두므로 값이 정확히 같아야 후보가 된다.
 */
public enum Purpose {

    /** 가볍게 즐기기 */
    CASUAL,

    /** 승리·랭크 상승 */
    RANK_UP,

    /** 초보자 학습 */
    LEARNING,

    /** 숙련자 중심 플레이 */
    EXPERT
}

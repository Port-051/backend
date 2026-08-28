package com.port051.queuemate.offer;

/** 제안 상태. 01 5.2 · 5.3. */
public enum OfferStatus {
    /** 응답을 기다리는 중. */
    PENDING,
    /** 전원 수락. 확정 단계로 넘어간다. */
    ALL_ACCEPTED,
    /** 누군가 거절해 깨졌다. */
    DECLINED,
    /** 제한시간이 지났다. */
    EXPIRED,
    /** 파티가 확정됐다. */
    CONFIRMED
}

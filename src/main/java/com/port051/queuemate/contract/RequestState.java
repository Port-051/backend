package com.port051.queuemate.contract;

/**
 * 요청 상태. 01 6.1의 목록 중 스파이크 범위(01 4·5장, 즉시 매칭)에 나타나는 것만 둔다.
 * 예약 매칭의 {@code 예약됨}과 확정 이후의 {@code 이탈함}·{@code 완료됨}은 6·7장이라 제외다(이슈 #48).
 *
 * <p>서버 내부 처리 단계는 사용자에게 노출하지 않는다(01 6.1). 여기 있는 다섯이 노출 가능한 전부다.
 */
public enum RequestState {
    /** 매칭 대기 중. 명단에 이름이 올라가 있다. */
    WAITING,
    /** 수락 대기 중. 제안에 묶여 있다. */
    OFFERED,
    /** 매칭 확정. */
    CONFIRMED,
    /** 취소됨. 사용자가 직접 취소했다. */
    CANCELLED,
    /** 매칭 실패. 최대 대기시간이 지났다(01 10.2). */
    FAILED;

    /** 명단에서 지워야 하는 종료 상태인가. */
    public boolean isTerminal() {
        return this == CONFIRMED || this == CANCELLED || this == FAILED;
    }
}

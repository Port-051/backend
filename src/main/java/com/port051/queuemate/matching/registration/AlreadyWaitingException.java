package com.port051.queuemate.matching.registration;

/**
 * 같은 사용자가 이미 대기 중이다. 01-functional-spec-mvp 9.2.
 *
 * @param userId    신청한 사용자
 * @param requestId 이미 대기 중인 요청. 표시가 그 사이 풀렸으면 {@code null}
 */
public class AlreadyWaitingException extends RuntimeException {

    private final long userId;
    private final Long requestId;

    public AlreadyWaitingException(long userId, Long requestId) {
        super("사용자 %d는 이미 대기 중이다 (요청 %s)".formatted(userId, requestId));
        this.userId = userId;
        this.requestId = requestId;
    }

    public long userId() {
        return userId;
    }

    /** 이미 대기 중인 요청 ID. 모를 수 있다. */
    public Long requestId() {
        return requestId;
    }
}

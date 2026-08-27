package com.port051.queuemate.matching.store;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 사용자당 대기 요청 하나. 01-functional-spec-mvp 9.2.
 *
 * <p>9.2는 "시간이 겹치는 요청을 중복 생성하지 못하게 한다"고 정한다. 즉시 매칭은 모두
 * 지금 시작하므로 <b>조건이 무엇이든 같은 사람의 두 요청은 항상 시간이 겹친다.</b>
 * 그래서 조합별이 아니라 사용자당 하나다.
 *
 * <p><b>{@code SET NX}를 쓰는 이유가 있다.</b> "기존 요청이 있는지 조회하고 없으면 넣는다"로 짜면
 * 조회와 삽입 사이에 다른 요청이 끼어든다. 더블클릭이나 재전송이면 두 요청이 거의 동시에 도착하므로
 * 이 틈이 실제로 벌어진다. {@code SET NX}는 확인과 삽입이 한 연산이라 틈이 없다.
 *
 * <p>이것이 새면 같은 사람의 요청 둘이 명단에 남고, 조건이 같으니 서로에 대해 4.1을 전부 통과해
 * <b>한 사람이 한 파티의 두 자리를 차지한다.</b> 다섯 불변식 중 어느 것도 그 위반을 잡지 못하므로
 * (02-technical-spec-supplement INV-6) 이 단계에서는 여기가 유일한 방어다.
 *
 * <p>유지 시간을 최대 대기시간과 맞춰 둔다. 표시를 떼지 못하고 죽어도 요청이 만료될 무렵
 * 같이 풀리므로 사용자가 영영 신청하지 못하는 상태가 되지 않는다.
 */
@Component
public class UserGuard {

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public UserGuard(StringRedisTemplate redis,
                     @Value("${queuemate.matching.max-wait:5m}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    /**
     * 이 사용자의 대기 요청 자리를 잡는다.
     *
     * @return 잡았으면 참. 이미 대기 중이면 거짓
     */
    public boolean take(long userId, long requestId) {
        return Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent(key(userId), String.valueOf(requestId), ttl));
    }

    /** 지금 대기 중인 요청 ID. 없으면 빈 값. */
    public Optional<Long> waitingRequestId(long userId) {
        String requestId = redis.opsForValue().get(key(userId));
        return requestId == null ? Optional.empty() : Optional.of(Long.valueOf(requestId));
    }

    /** 자리를 비운다. 취소 · 만료 · 파티 확정에서 부른다. */
    public void release(long userId) {
        redis.delete(key(userId));
    }

    /** 여러 사용자의 자리를 한 번에 비운다. */
    public void releaseAll(java.util.List<Long> userIds) {
        if (userIds.isEmpty()) {
            return;
        }
        redis.delete(userIds.stream().map(UserGuard::key).toList());
    }

    private static String key(long userId) {
        return "user:" + userId + ":waiting";
    }
}

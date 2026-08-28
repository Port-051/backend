package com.port051.queuemate.offer;

import com.port051.queuemate.config.MatchingProperties;
import com.port051.queuemate.contract.RedisKeys;
import java.util.Collection;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 실패 조합 재제안 억제. 01 5.4.
 *
 * <p>조합은 <b>참가 요청 식별자의 집합</b>이다. 구성원이 한 명이라도 다르면 다른 조합이라
 * 억제 대상이 아니다. 그래서 요청 id를 정렬해 이어붙인 것을 그대로 키로 쓴다.
 *
 * <p><b>명시적 거절로 깨진 조합만 억제한다.</b> 시간초과까지 억제하면 알림이 늦게 닿은
 * 참가자 한 명 때문에 정상 조합이 10분간 막힌다. 거절은 의사 표시지만 시간초과는 전달 실패일 수 있다.
 *
 * <p>기록은 TTL로 사라진다. 영구 보관하지 않으므로(01 5.4) 별도 정리가 필요 없고,
 * Redis에 두었으므로 인스턴스가 여러 대여도 공유된다.
 */
@Component
public class ComboSuppressor {

    private final StringRedisTemplate redis;
    private final MatchingProperties properties;

    public ComboSuppressor(StringRedisTemplate redis, MatchingProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** 조합 해시. 집합이므로 정렬해서 순서를 없앤다. */
    public static String hash(Collection<Long> requestIds) {
        List<Long> sorted = requestIds.stream().sorted().toList();
        StringBuilder sb = new StringBuilder(sorted.size() * 8);
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) sb.append('-');
            sb.append(sorted.get(i));
        }
        return sb.toString();
    }

    public boolean isSuppressed(Collection<Long> requestIds) {
        return Boolean.TRUE.equals(redis.hasKey(RedisKeys.suppress(hash(requestIds))));
    }

    /** 명시적 거절로 깨진 조합을 억제한다. */
    public void suppress(Collection<Long> requestIds) {
        redis.opsForValue()
                .set(RedisKeys.suppress(hash(requestIds)), "1", properties.suppressTtl());
    }
}

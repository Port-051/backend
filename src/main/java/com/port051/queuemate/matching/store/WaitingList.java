package com.port051.queuemate.matching.store;

import com.port051.queuemate.matching.domain.MatchRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 대기 명단 {@code mq:...}. 05-realtime-matching-contract 1장 — "누가 먼저 왔는지 순서만".
 *
 * <p>Sorted Set을 쓴다. score를 {@code requestedAt}으로 두면 Redis가 정렬을 대신 해 주므로
 * 매칭 루프는 앞에서부터 읽기만 하면 된다.
 *
 * <p><b>요청 ID를 자리수를 채운 문자열로 넣는 이유가 있다.</b> 4.3의 전순서는
 * {@code (신청 시각, 요청 ID)}인데 Sorted Set의 score는 숫자 하나뿐이라 두 값을 담을 수 없다.
 * 남은 동률은 Redis가 <b>멤버를 사전순으로</b> 가르는데, 요청 ID를 그대로 넣으면
 * {@code "10" < "9"}가 되어 늦게 신청한 사람이 앞에 선다. 자리수를 맞춰 두면
 * 사전순과 숫자 크기 순이 일치한다.
 *
 * <p>score에 두 값을 합쳐 담는 방법도 있으나 쓸 수 없다. score는 {@code double}이고
 * 정수를 정확히 담을 수 있는 폭이 53비트인데, epoch millis가 이미 41비트를 쓴다.
 * 남는 12비트로는 요청 ID를 4096개밖에 구분하지 못한다.
 */
@Component
public class WaitingList {

    /** {@code Long.MAX_VALUE}가 19자리다. 이보다 짧으면 큰 요청 ID에서 다시 순서가 뒤집힌다. */
    private static final String MEMBER_FORMAT = "%019d";

    /**
     * 전원을 지우거나 아무도 지우지 않는다.
     *
     * <p>먼저 전원이 명단에 있는지 확인하고, 한 명이라도 없으면 아무것도 건드리지 않는다.
     * Redis는 스크립트를 실행하는 동안 다른 명령을 받지 않으므로 확인과 삭제 사이에 틈이 없다.
     */
    private static final RedisScript<Long> REMOVE_ALL = new DefaultRedisScript<>("""
            for i = 1, #ARGV do
              if redis.call('ZSCORE', KEYS[1], ARGV[i]) == false then
                return 0
              end
            end
            for i = 1, #ARGV do
              redis.call('ZREM', KEYS[1], ARGV[i])
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    public WaitingList(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 명단에 올린다. 이미 있으면 순서만 갱신된다. */
    public void add(MatchRequest request) {
        redis.opsForZSet().add(key(), member(request.requestId()), request.requestedAt());
    }

    /** 명단에서 지운다. 취소 · 만료 · 파티 확정에서 부른다. */
    public void remove(long requestId) {
        redis.opsForZSet().remove(key(), member(requestId));
    }

    /**
     * 전원을 명단에서 지우거나 아무도 지우지 않는다.
     *
     * <p>{@link ClaimStore}와 다른 선점 방식이다. 배정 중 표시를 따로 두는 대신
     * <b>명단에서 빼내는 것 자체를 선점으로</b> 삼는다. 명단에서 사라지면 다른 인스턴스가
     * 그 요청을 후보로 잡을 수 없으므로 같은 사람이 두 파티에 들어가지 않는다.
     *
     * <p>{@code ZREM}은 실제로 지운 개수를 돌려준다. 그 값이 요청한 수와 같을 때만
     * 전원을 잡은 것이고, 하나라도 모자라면 <b>이미 지운 것을 되돌려 놓아야</b> 한다.
     * 되돌리려면 원래 score를 알아야 하므로 지우기 전에 먼저 읽어 둔다.
     * 이 확인·삭제·복구가 한 스크립트 안에서 끝나야 다른 인스턴스가 중간 상태를 보지 않는다.
     *
     * @return 전원을 지웠으면 참. 빈 목록은 잡은 것이 없으므로 거짓이다
     */
    public boolean removeAll(List<Long> requestIds) {
        if (requestIds.isEmpty()) {
            return false;
        }
        Long removed = redis.execute(REMOVE_ALL, List.of(key()),
                requestIds.stream().map(WaitingList::member).toArray());
        return removed != null && removed == 1;
    }

    /** 대기 중인 요청 ID를 전순서대로 읽는다. */
    public List<Long> requestIds() {
        Set<String> members = redis.opsForZSet().range(key(), 0, -1);
        return members == null ? List.of() : members.stream().map(Long::valueOf).toList();
    }

    /**
     * 신청 시각이 {@code threshold} 이하인 요청 ID. 만료 대상을 고르는 데 쓴다.
     *
     * <p>score가 곧 {@code requestedAt}이므로 만료 판정이 <b>score 범위 조회</b>가 된다.
     * Sorted Set을 고른 값이 여기서 나온다. List였다면 전체를 훑어야 했다.
     */
    public List<Long> requestIdsUpTo(long threshold) {
        Set<String> members = redis.opsForZSet().rangeByScore(key(), Double.NEGATIVE_INFINITY, threshold);
        return members == null ? List.of() : members.stream().map(Long::valueOf).toList();
    }

    /**
     * 신청 시각이 {@code threshold} 이하인 요청을 명단에서 한 번에 지운다.
     *
     * <p>개별 항목에 TTL을 걸 수 없어 이 방식을 쓴다. Redis의 TTL은 <b>키 단위</b>라
     * Sorted Set에 걸면 대기 명단 전체가 사라진다.
     *
     * @return 지운 개수
     */
    public long removeUpTo(long threshold) {
        Long removed = redis.opsForZSet().removeRangeByScore(key(), Double.NEGATIVE_INFINITY, threshold);
        return removed == null ? 0 : removed;
    }

    /** 대기 인원. 3.1이 조건 입력 화면에 표시하라고 한 값이다. */
    public long size() {
        Long size = redis.opsForZSet().size(key());
        return size == null ? 0 : size;
    }

    /** 사전순이 숫자 크기 순과 같아지도록 자리수를 채운다. */
    private static String member(long requestId) {
        return MEMBER_FORMAT.formatted(requestId);
    }

    private static String key() {
        return "mq:instant";
    }
}

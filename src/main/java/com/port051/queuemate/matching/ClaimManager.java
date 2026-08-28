package com.port051.queuemate.matching;

import com.port051.queuemate.config.MatchingProperties;
import com.port051.queuemate.contract.RedisKeys;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 좌석 선점. 계약(05 1절)의 {@code claim:{requestId}}를 다룬다.
 *
 * <p><b>선점 단위를 요청 하나로 잡았다.</b> 계약은 "배정 중 표시"라고만 했고 자료구조·TTL·
 * 떼는 시점을 전부 자율로 남겼다(05 자율). 여기서 고른 것은 이렇다.
 *
 * <table>
 *   <tr><th>축</th><th>고른 것</th><th>왜</th></tr>
 *   <tr><td>단위</td><td>요청 하나</td>
 *       <td>경합의 실체가 "이 사람이 두 파티에 들어가는가"라 요청이 곧 좌석이다.
 *           파티 단위로 잡으면 아직 존재하지 않는 파티에 락을 걸어야 한다</td></tr>
 *   <tr><td>범위</td><td>후보 전원 all-or-nothing</td>
 *       <td>하나씩 잡으면 중간까지 성공하고 실패하는 상태가 남아, 부하 구간에서
 *           서로가 서로의 앞부분을 보고 물러나는 교착이 반복된다</td></tr>
 *   <tr><td>원자성</td><td>Lua 한 덩어리</td>
 *       <td>검사와 쓰기 사이에 다른 인스턴스의 명령이 끼지 못한다</td></tr>
 *   <tr><td>TTL</td><td>{@code claimTtl} (기본 5초)</td>
 *       <td>제안 수명(20초)보다 짧다. 이유는 아래</td></tr>
 * </table>
 *
 * <p><b>TTL이 제안 수명보다 짧은 것은 의도다.</b> 제안이 만들어지는 순간 참가자는 명단에서
 * 빠지므로(→ {@code detachForOffer}), 그 뒤로는 선점이 없어도 다른 매처가 집어갈 수 없다.
 * 선점이 실제로 필요한 구간은 <b>후보를 고른 뒤 제안을 만들기까지</b>의 짧은 순간뿐이다.
 * 길게 잡으면 매처가 죽었을 때 그 요청들이 TTL만큼 통째로 얼어붙는다.
 *
 * <p>02 1.3이 못박은 대로 <b>선점은 최적화이고 정합성은 확정 시점의 검사가 보장한다</b>.
 * 선점이 전부 사라져도 {@code confirm_party.lua}의 {@code member:{requestId}} 검사가
 * INV-3을 막는다 — 04단계의 "락 만료 중 작업 지속"이 여기서 재현 가능한 이유다.
 */
@Component
public class ClaimManager {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> claimAllScript;
    private final RedisScript<Long> releaseClaimsScript;
    private final MatchingProperties properties;

    public ClaimManager(
            StringRedisTemplate redis,
            RedisScript<Long> claimAllScript,
            RedisScript<Long> releaseClaimsScript,
            MatchingProperties properties) {
        this.redis = redis;
        this.claimAllScript = claimAllScript;
        this.releaseClaimsScript = releaseClaimsScript;
        this.properties = properties;
    }

    public static String newToken() {
        return UUID.randomUUID().toString();
    }

    /**
     * 후보 전원을 한 번에 선점한다.
     *
     * @return 전원 성공하면 true. 하나라도 이미 잡혀 있으면 <b>아무것도 잡지 않고</b> false
     */
    public boolean claimAll(Collection<Long> requestIds, String token) {
        if (requestIds.isEmpty()) return false;
        List<String> keys = requestIds.stream().map(RedisKeys::claim).toList();
        Long result =
                redis.execute(
                        claimAllScript,
                        keys,
                        token,
                        String.valueOf(properties.claimTtl().toMillis()));
        return result != null && result == 1L;
    }

    /** 내가 건 선점만 뗀다. 남의 것은 건드리지 않는다. */
    public void release(Collection<Long> requestIds, String token) {
        if (requestIds.isEmpty()) return;
        List<String> keys = requestIds.stream().map(RedisKeys::claim).toList();
        redis.execute(releaseClaimsScript, keys, token);
    }

    public boolean isClaimed(long requestId) {
        return Boolean.TRUE.equals(redis.hasKey(RedisKeys.claim(requestId)));
    }
}

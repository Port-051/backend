package com.port051.queuemate.matching.redis;

import com.port051.queuemate.matching.domain.ComposedParty;
import com.port051.queuemate.matching.domain.MatchRequestView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 좌석 선점. README 2단계의 비교 대상 중 <b>Lua 원자 선점</b> 하나를 구현한 것이다
 * (04 9.4 — 확정으로 읽지 않는다).
 *
 * <p>요청 단위와 <b>사용자 단위</b> 둘 다에 포스트잇을 붙인다. 사용자 단위가 필요한 이유는
 * 한 사람이 요청을 두 개 넣으면 요청 단위 선점만으로는 같은 사람이 두 제안에 동시에
 * 들어갈 수 있기 때문이다. DB 배제 제약(INV-2)이 없는 이 스파이크에서 그 역할을 대신한다.
 */
@Component
public class ClaimService {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> claimScript;
    private final RedisScript<Long> releaseScript;

    public ClaimService(StringRedisTemplate redis,
                        RedisScript<Long> claimScript,
                        RedisScript<Long> releaseScript) {
        this.redis = redis;
        this.claimScript = claimScript;
        this.releaseScript = releaseScript;
    }

    /** 전원 선점에 성공하면 true. 하나라도 실패하면 붙인 것을 전부 떼고 false. */
    public boolean claim(ComposedParty party, long offerId, Duration ttl) {
        List<MatchRequestView> members = party.members();
        List<String> keys = new ArrayList<>(members.size() * 2 + 1);
        members.forEach(m -> keys.add(Keys.claim(m.requestId())));
        members.forEach(m -> keys.add(Keys.userClaim(m.userId())));
        keys.add(Keys.partition(members.getFirst().queue(), members.getFirst().targetSize()));

        List<String> args = new ArrayList<>();
        args.add(String.valueOf(offerId));
        args.add(String.valueOf(ttl.toSeconds()));
        args.add(String.valueOf(members.size()));
        members.forEach(m -> args.add(String.valueOf(m.requestId())));

        Long result = redis.execute(claimScript, keys, args.toArray());
        return result != null && result == 1L;
    }

    public void release(List<Long> requestIds, List<Long> userIds, long offerId) {
        List<String> keys = new ArrayList<>();
        requestIds.forEach(id -> keys.add(Keys.claim(id)));
        userIds.forEach(id -> keys.add(Keys.userClaim(id)));
        redis.execute(releaseScript, keys, String.valueOf(offerId));
    }
}

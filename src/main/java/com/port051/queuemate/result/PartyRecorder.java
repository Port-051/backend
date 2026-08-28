package com.port051.queuemate.result;

import com.port051.queuemate.contract.MatchRequestPayload;
import com.port051.queuemate.contract.Position;
import com.port051.queuemate.contract.RedisKeys;
import com.port051.queuemate.matching.VoiceCompatibility;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 확정 파티를 Redis에 남긴다. <b>판정 게이트가 읽는 자리다.</b>
 *
 * <p>02 2장은 "위반 검출은 로그가 아니라 데이터베이스를 직접 검사해서 계수한다"고 못박았지만,
 * 이 스파이크는 DB를 쓰지 않기로 했다(이슈 #48). 그래서 확정 결과를 Redis에 남기고
 * {@code load-test/verify-invariants.sh}가 SCAN으로 계수한다. 이슈 #48의
 * "판정 게이트를 무엇으로 하나" 미체크 항목에 대한 답이다.
 *
 * <p><b>이 대체가 잃는 것.</b> 02가 검출을 DB에 둔 이유는 "부하를 넣는 도구와 위반을 세는 도구가
 * 분리돼 있어야 사각이 없다"는 것이었다(04 4.4). Redis에 쓰고 Redis에서 세면
 * <b>쓰기 경로의 버그가 검출 경로에도 그대로 반영된다</b> — 예를 들어 확정을 아예 기록하지 않으면
 * 위반이 0건으로 나온다. 이 사각은 "확정 수 == 부하 스크립트가 받은 PARTY_CONFIRMED 수"를
 * 함께 확인해서 메운다. 검증 스크립트가 그 대조를 한다.
 *
 * <p>남기는 것은 넷이다.
 * <pre>
 *   parties                   파티 id 집합 — 스캔 진입점
 *   party:{id}                큐 · 정원 · 시각 · 목적 · 음성 여부
 *   party:{id}:members        requestId → "포지션|userId"
 *   member:{requestId}        requestId → partyId      ← INV-3을 강제하는 자리
 * </pre>
 */
@Component
public class PartyRecorder {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> confirmPartyScript;

    public PartyRecorder(StringRedisTemplate redis, RedisScript<Long> confirmPartyScript) {
        this.redis = redis;
        this.confirmPartyScript = confirmPartyScript;
    }

    public long nextPartyId() {
        Long id = redis.opsForValue().increment(RedisKeys.SEQ_PARTY);
        return id == null ? 1L : id;
    }

    /**
     * 파티를 확정한다.
     *
     * <p><b>종료 예정 시각은 참가자 중 가장 짧은 예상 플레이시간으로 잡는다.</b>
     * 01 3.1은 "확정 시각에 예상 플레이시간을 더한 값"이라고만 했고 참가자마다 값이 다를 때
     * 무엇을 쓸지 정하지 않았다. 가장 긴 쪽을 쓰면 먼저 빠져야 하는 사람의 시간이 파티에 묶여
     * INV-2(시간 겹침)의 판정이 실제보다 넓어진다. 짧은 쪽이 안전한 방향이다.
     *
     * @return 확정했으면 partyId, 이미 배정된 요청이 있어 중단했으면 비어 있음
     */
    public java.util.Optional<Long> confirm(
            List<MatchRequestPayload> party, Map<Long, Position> positions, long startAt) {

        long partyId = nextPartyId();
        MatchRequestPayload base = party.get(0);

        int shortestPlayMinutes =
                party.stream().mapToInt(MatchRequestPayload::playMinutes).min().orElse(0);
        long endAt = startAt + shortestPlayMinutes * 60_000L;

        boolean voiceParty =
                VoiceCompatibility.isVoiceParty(
                        party.stream().map(MatchRequestPayload::voiceMode).toList());

        List<String> args = new ArrayList<>();
        args.add(String.valueOf(partyId));
        args.add(base.queue().name());
        args.add(String.valueOf(base.targetSize()));
        args.add(String.valueOf(startAt));
        args.add(String.valueOf(endAt));
        args.add(base.purpose().name());
        args.add(String.valueOf(voiceParty));
        args.add(String.valueOf(party.size()));
        party.forEach(m -> args.add(String.valueOf(m.requestId())));
        party.forEach(m -> args.add(String.valueOf(m.userId())));
        party.forEach(m -> args.add(positions.get(m.requestId()).name()));

        Long result =
                redis.execute(
                        confirmPartyScript,
                        List.of(
                                RedisKeys.PARTY_INDEX,
                                RedisKeys.party(partyId),
                                RedisKeys.partyMembers(partyId),
                                RedisKeys.waitingQueue(base.queue(), base.targetSize())),
                        args.toArray());

        return result != null && result == 1L
                ? java.util.Optional.of(partyId)
                : java.util.Optional.empty();
    }
}

package com.port051.queuemate.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.port051.queuemate.contract.MatchRequestPayload;
import com.port051.queuemate.contract.Position;
import com.port051.queuemate.contract.Queue;
import com.port051.queuemate.contract.RedisKeys;
import com.port051.queuemate.contract.RequestState;
import com.port051.queuemate.offer.Offer;
import com.port051.queuemate.offer.OfferService;
import com.port051.queuemate.offer.OfferStore;
import com.port051.queuemate.support.RedisTestBase;
import com.port051.queuemate.support.Requests;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 요청 접수 → 매칭 → 제안 → 수락 → 확정까지 한 줄로 흐르는지 본다. 01 4·5장.
 *
 * <p>틱은 테스트가 직접 돌린다. 백그라운드 스케줄이 끼면 "이 입력에 이 출력"이 흔들려
 * 01 4.3의 결정성을 확인할 수 없다.
 */
class MatchingFlowTest extends RedisTestBase {

    @Autowired MatchRequestStore requests;
    @Autowired MatchingLoop loop;
    @Autowired OfferStore offers;
    @Autowired OfferService offerService;

    /** 다섯 포지션을 하나씩 맡는 5인. 조건은 전부 통과하도록 깔아 둔다. */
    private List<MatchRequestPayload> enqueueFive(long startId) {
        Position[] positions = Position.values();
        List<MatchRequestPayload> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            MatchRequestPayload payload = Requests.at(startId + i, positions[i]);
            requests.enqueue(payload);
            created.add(payload);
        }
        return created;
    }

    @Test
    @DisplayName("정원이 차면 제안이 만들어지고 참가자는 명단에서 빠진다")
    void proposesWhenPartyIsFull() {
        List<MatchRequestPayload> party = enqueueFive(1);

        loop.runPartition(Queue.FLEX, 5);

        for (MatchRequestPayload member : party) {
            assertThat(requests.state(member.requestId())).isEqualTo(RequestState.OFFERED);
            assertThat(requests.currentOffer(member.requestId())).isPresent();
        }
        // 01 5.1 경로 — 제안에 묶인 요청은 대기 명단에 남아 있지 않다.
        assertThat(redis.opsForZSet().size(RedisKeys.waitingQueue(Queue.FLEX, 5))).isZero();
    }

    @Test
    @DisplayName("정원이 안 차면 아무도 건드리지 않는다")
    void doesNothingBelowTargetSize() {
        for (int i = 0; i < 4; i++) {
            requests.enqueue(Requests.at(1 + i, Position.values()[i]));
        }

        loop.runPartition(Queue.FLEX, 5);

        assertThat(redis.opsForZSet().size(RedisKeys.waitingQueue(Queue.FLEX, 5))).isEqualTo(4);
        assertThat(requests.state(1L)).isEqualTo(RequestState.WAITING);
    }

    @Test
    @DisplayName("전원이 수락해야 확정된다 — 01 5.3")
    void confirmsOnlyWhenEveryoneAccepts() {
        List<MatchRequestPayload> party = enqueueFive(1);
        loop.runPartition(Queue.FLEX, 5);

        long offerId = requests.currentOffer(1L).orElseThrow();

        // 넷만 수락한다.
        for (int i = 0; i < 4; i++) {
            offerService.respond(offerId, party.get(i).requestId(), true, true);
            assertThat(requests.state(party.get(i).requestId())).isEqualTo(RequestState.OFFERED);
        }
        assertThat(redis.opsForSet().size(RedisKeys.PARTY_INDEX)).isZero();

        // 마지막 한 명이 수락하는 순간 확정된다.
        offerService.respond(offerId, party.get(4).requestId(), true, true);

        assertThat(redis.opsForSet().size(RedisKeys.PARTY_INDEX)).isEqualTo(1);
        for (MatchRequestPayload member : party) {
            assertThat(requests.state(member.requestId())).isEqualTo(RequestState.CONFIRMED);
            assertThat(requests.partyOf(member.requestId())).isPresent();
        }
    }

    @Test
    @DisplayName("중복 수락은 한 번만 처리한다 — 01 5.2")
    void acceptIsIdempotent() {
        List<MatchRequestPayload> party = enqueueFive(1);
        loop.runPartition(Queue.FLEX, 5);
        long offerId = requests.currentOffer(1L).orElseThrow();

        // 1번이 열 번 누른다. 네트워크 재전송과 구분되지 않는 상황이다.
        for (int i = 0; i < 10; i++) {
            OfferService.RespondResult result =
                    offerService.respond(offerId, 1L, true, true);
            assertThat(result.outcome()).isEqualTo("OK");
        }

        long accepted =
                offers.responses(offerId).values().stream().filter("ACCEPTED"::equals).count();
        assertThat(accepted).isEqualTo(1); // 열 번 눌러도 한 명이다
        assertThat(redis.opsForSet().size(RedisKeys.PARTY_INDEX)).isZero();
    }

    @Test
    @DisplayName("한 명이 거절하면 나머지는 대기 순서를 유지한 채 재대기한다 — 01 5.3")
    void survivorsKeepTheirPlaceInQueue() {
        List<MatchRequestPayload> party = enqueueFive(1);
        loop.runPartition(Queue.FLEX, 5);
        long offerId = requests.currentOffer(1L).orElseThrow();

        offerService.respond(offerId, 1L, true, true);   // 수락
        offerService.respond(offerId, 3L, false, true);  // 거절 — 계속 찾기

        for (long requestId : List.of(1L, 2L, 4L, 5L)) {
            assertThat(requests.state(requestId)).isEqualTo(RequestState.WAITING);
        }
        // 거절한 사람도 `계속 찾기`를 골랐으므로 명단에 남는다.
        assertThat(requests.state(3L)).isEqualTo(RequestState.WAITING);
        assertThat(redis.opsForZSet().size(RedisKeys.waitingQueue(Queue.FLEX, 5))).isEqualTo(5);

        // 순서가 유지되는지 — 점수가 원래 신청 시각 그대로여야 한다.
        Double score =
                redis.opsForZSet()
                        .score(RedisKeys.waitingQueue(Queue.FLEX, 5), String.format("%019d", 1L));
        assertThat(score).isEqualTo((double) party.get(0).requestedAt());
    }

    @Test
    @DisplayName("거절로 깨진 조합은 다시 제안하지 않는다 — 01 5.4")
    void declinedComboIsSuppressed() {
        enqueueFive(1);
        loop.runPartition(Queue.FLEX, 5);
        long offerId = requests.currentOffer(1L).orElseThrow();

        offerService.respond(offerId, 3L, false, true); // 명시적 거절

        // 같은 다섯 명이 그대로 명단에 있지만, 이 조합은 억제돼 있다.
        loop.runPartition(Queue.FLEX, 5);

        assertThat(requests.currentOffer(1L)).isEmpty();
        assertThat(requests.state(1L)).isEqualTo(RequestState.WAITING);
    }

    @Test
    @DisplayName("구성원이 한 명만 달라도 다른 조합이라 제안된다 — 01 5.4")
    void differentComboIsNotSuppressed() {
        enqueueFive(1);
        loop.runPartition(Queue.FLEX, 5);
        long offerId = requests.currentOffer(1L).orElseThrow();
        offerService.respond(offerId, 3L, false, false); // 거절하고 매칭 종료

        // 3번이 빠졌으므로 6번을 넣으면 구성원이 하나 다른 새 조합이 된다.
        requests.enqueue(Requests.at(6, Position.MID));

        loop.runPartition(Queue.FLEX, 5);

        assertThat(requests.currentOffer(1L)).isPresent();
        assertThat(requests.state(6L)).isEqualTo(RequestState.OFFERED);
    }

    @Test
    @DisplayName("같은 입력이면 항상 같은 파티가 나온다 — 01 4.3 결정성")
    void buildIsDeterministic() {
        // 신청 시각이 전부 같은 열 명. 이 조건에서 순서가 흔들리면 요청 ID로 갈려야 한다.
        List<MatchRequestPayload> all = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            MatchRequestPayload payload =
                    Requests.of(
                            100 + i, 100 + i, Queue.FLEX, 5,
                            com.port051.queuemate.contract.Purpose.RANK_UP,
                            com.port051.queuemate.contract.VoiceMode.POSSIBLE,
                            14, 1, 28,
                            com.port051.queuemate.support.Requests.BASE_REQUESTED_AT, // ← 신청 시각이 전부 동일
                            Position.values()[i % 5], Position.values());
            all.add(payload);
        }

        List<Long> firstRun = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            redis.getConnectionFactory().getConnection().serverCommands().flushAll();
            all.forEach(requests::enqueue);

            List<Long> ordered = requests.waitingIds(Queue.FLEX, 5, 500);
            if (firstRun == null) firstRun = ordered;
            // 신청 시각이 같아도 요청 ID 오름차순으로 고정된다.
            assertThat(ordered).isEqualTo(firstRun);
            assertThat(ordered).isSorted();
        }
    }

    @Test
    @DisplayName("취소가 제안보다 늦으면 409와 제안 id를 돌려준다 — 01 3.9")
    void cancelLosesToOffer() {
        enqueueFive(1);
        loop.runPartition(Queue.FLEX, 5);

        MatchRequestPayload payload = Requests.at(1, Position.TOP);
        MatchRequestStore.CancelResult result = requests.cancel(1L, Queue.FLEX, 5);

        assertThat(result.cancelled()).isFalse();
        assertThat(result.offerId()).isNotNull();
    }

    @Test
    @DisplayName("대기 중이면 취소가 이긴다 — 01 3.9")
    void cancelWinsWhileWaiting() {
        requests.enqueue(Requests.at(1, Position.TOP));

        MatchRequestStore.CancelResult result = requests.cancel(1L, Queue.FLEX, 5);

        assertThat(result.cancelled()).isTrue();
        assertThat(requests.state(1L)).isEqualTo(RequestState.CANCELLED);
        assertThat(redis.opsForZSet().size(RedisKeys.waitingQueue(Queue.FLEX, 5))).isZero();
    }

    @Test
    @DisplayName("확정된 파티의 포지션은 겹치지 않는다 — INV-4")
    void confirmedPartyHasDistinctPositions() {
        enqueueFive(1);
        loop.runPartition(Queue.FLEX, 5);
        long offerId = requests.currentOffer(1L).orElseThrow();
        for (long requestId = 1; requestId <= 5; requestId++) {
            offerService.respond(offerId, requestId, true, true);
        }

        Long partyId = requests.partyOf(1L).orElseThrow();
        Map<Object, Object> members = redis.opsForHash().entries(RedisKeys.partyMembers(partyId));

        assertThat(members).hasSize(5);
        List<String> positions =
                members.values().stream().map(v -> String.valueOf(v).split("\\|")[0]).toList();
        assertThat(positions).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("제안 만료는 억제하지 않는다 — 01 5.4")
    void expiryDoesNotSuppress() {
        enqueueFive(1);
        loop.runPartition(Queue.FLEX, 5);
        long offerId = requests.currentOffer(1L).orElseThrow();

        Offer offer = offers.load(offerId).orElseThrow();
        // 만료 시각을 과거로 밀어 시간초과를 만든다.
        redis.opsForHash().put(RedisKeys.offer(offerId), "expiresAt", "1");
        assertThat(offers.expire(offerId, System.currentTimeMillis())).isTrue();
        offerService.handleExpired(offers.load(offerId).orElseThrow());

        // 전원 재대기. 억제되지 않았으므로 같은 조합이 다시 제안된다.
        loop.runPartition(Queue.FLEX, 5);
        assertThat(requests.currentOffer(1L)).isPresent();
    }
}

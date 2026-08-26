package com.port051.queuemate.matching.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 01-functional-spec-mvp 4.3 후보 처리 순서. */
class PartyMatcherTest {

    /**
     * 요청 하나를 짓는다. 지정하지 않은 조건은 서로 맞는 기본값이라
     * 각 테스트는 검사하려는 것만 바꾸면 된다.
     */
    private static final class Req {

        private final long id;
        private long userId;
        private long requestedAt;
        private GameQueue queue = GameQueue.SOLO_DUO;
        private int targetSize = 2;
        private Purpose purpose = Purpose.RANK_UP;
        private VoiceMode voiceMode = VoiceMode.POSSIBLE;
        private Position primary = Position.MID;
        private List<Position> subs = List.of(Position.TOP, Position.JUNGLE, Position.BOTTOM, Position.SUPPORT);
        private int tierOrder = 14;
        private int allowedTierMinOrder = 1;
        private int allowedTierMaxOrder = 30;

        private Req(long id) {
            this.id = id;
            this.userId = id;
            this.requestedAt = id;
        }

        static Req id(long id) {
            return new Req(id);
        }

        Req at(long requestedAt) {
            this.requestedAt = requestedAt;
            return this;
        }

        Req user(long userId) {
            this.userId = userId;
            return this;
        }

        Req queue(GameQueue queue) {
            this.queue = queue;
            return this;
        }

        Req size(int targetSize) {
            this.targetSize = targetSize;
            return this;
        }

        Req purpose(Purpose purpose) {
            this.purpose = purpose;
            return this;
        }

        Req voice(VoiceMode voiceMode) {
            this.voiceMode = voiceMode;
            return this;
        }

        Req positions(Position primary, Position... subs) {
            this.primary = primary;
            this.subs = List.of(subs);
            return this;
        }

        Req tier(int tierOrder, int min, int max) {
            this.tierOrder = tierOrder;
            this.allowedTierMinOrder = min;
            this.allowedTierMaxOrder = max;
            return this;
        }

        MatchRequest build() {
            return new MatchRequest(
                    id, userId, queue, targetSize, purpose, 120, voiceMode,
                    primary, subs, tierOrder, allowedTierMinOrder, allowedTierMaxOrder, requestedAt);
        }
    }

    private static List<MatchRequest> waiting(Req... requests) {
        List<MatchRequest> list = new ArrayList<>();
        for (Req request : requests) {
            list.add(request.build());
        }
        return list;
    }

    private static List<Long> requestIds(Party party) {
        return party.members().stream().map(MatchRequest::requestId).toList();
    }

    @Test
    @DisplayName("조건이 맞는 둘은 파티가 된다")
    void twoCompatibleRequestsFormAParty() {
        List<Party> parties = PartyMatcher.match(waiting(Req.id(1), Req.id(2)));

        assertThat(parties).hasSize(1);
        assertThat(parties.getFirst().size()).isEqualTo(2);
        assertThat(requestIds(parties.getFirst())).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("목표 인원을 못 채우면 파티가 성립하지 않는다")
    void doesNotFormAnUnderfilledParty() {
        List<Party> parties = PartyMatcher.match(waiting(
                Req.id(1).size(5),
                Req.id(2).size(5),
                Req.id(3).size(5)));

        assertThat(parties).isEmpty();
    }

    @Test
    @DisplayName("대기 요청이 없으면 파티도 없다")
    void emptyWaitingListProducesNoParties() {
        assertThat(PartyMatcher.match(List.of())).isEmpty();
    }

    @Test
    @DisplayName("한 요청이 두 파티에 들어가지 않는다")
    void aRequestNeverJoinsTwoParties() {
        List<Party> parties = PartyMatcher.match(waiting(
                Req.id(1), Req.id(2), Req.id(3), Req.id(4), Req.id(5)));

        assertThat(parties).hasSize(2);
        List<Long> everyone = parties.stream().flatMap(p -> requestIds(p).stream()).toList();
        assertThat(everyone).doesNotHaveDuplicates();
        // 다섯 번째는 짝이 없어 대기로 남는다.
        assertThat(everyone).containsExactly(1L, 2L, 3L, 4L);
    }

    @Nested
    @DisplayName("전원 대조")
    class AgainstEveryMember {

        @Test
        @DisplayName("기준과는 호환이지만 서로 호환되지 않는 둘은 한 파티에 들어가지 않는다")
        void rejectsCandidatesThatClashWithAnAlreadyAdoptedMember() {
            // 철수(가능)를 기준으로 잡으면 영희(필수)도 민수(사용하지 않음)도 각각은 호환이다.
            // 그러나 영희와 민수는 4.2 표에서 유일하게 막히는 조합이다.
            List<Party> parties = PartyMatcher.match(waiting(
                    Req.id(1).size(3).voice(VoiceMode.POSSIBLE),
                    Req.id(2).size(3).voice(VoiceMode.REQUIRED),
                    Req.id(3).size(3).voice(VoiceMode.NOT_USED)));

            assertThat(parties).isEmpty();
        }

        @Test
        @DisplayName("허용 티어 범위도 전원과 대조한다")
        void checksTierRangeAgainstEveryMember() {
            // 1번은 범위가 넓어 둘 다 받아들이지만, 2번과 3번은 서로를 받아들이지 않는다.
            List<Party> parties = PartyMatcher.match(waiting(
                    Req.id(1).size(3).tier(15, 1, 30),
                    Req.id(2).size(3).tier(10, 1, 20),
                    Req.id(3).size(3).tier(25, 21, 30)));

            assertThat(parties).isEmpty();
        }

        @Test
        @DisplayName("서로 맞는 후보가 있으면 그쪽으로 채운다")
        void stillFillsWhenACompatibleCandidateExists() {
            List<Party> parties = PartyMatcher.match(waiting(
                    Req.id(1).size(3).voice(VoiceMode.POSSIBLE),
                    Req.id(2).size(3).voice(VoiceMode.REQUIRED),
                    Req.id(3).size(3).voice(VoiceMode.NOT_USED),
                    Req.id(4).size(3).voice(VoiceMode.REQUIRED)));

            assertThat(parties).hasSize(1);
            // 3번(사용하지 않음)은 2번 때문에 걸러지고 4번이 대신 들어간다.
            assertThat(requestIds(parties.getFirst())).containsExactly(1L, 2L, 4L);
        }
    }

    @Nested
    @DisplayName("후보 처리 순서")
    class Ordering {

        @Test
        @DisplayName("신청이 빠른 요청이 먼저 기준이 된다")
        void theEarliestRequestBecomesTheBase() {
            List<Party> parties = PartyMatcher.match(waiting(
                    Req.id(10).at(300),
                    Req.id(20).at(100),
                    Req.id(30).at(200)));

            assertThat(parties).hasSize(1);
            assertThat(requestIds(parties.getFirst())).containsExactly(20L, 30L);
        }

        @Test
        @DisplayName("신청 시각이 같으면 요청 ID로 가른다")
        void breaksTiesByRequestId() {
            List<Party> parties = PartyMatcher.match(waiting(
                    Req.id(30).at(100),
                    Req.id(10).at(100),
                    Req.id(20).at(100)));

            assertThat(parties).hasSize(1);
            assertThat(requestIds(parties.getFirst())).containsExactly(10L, 20L);
        }

        @Test
        @DisplayName("입력 순서가 달라도 결과가 같다")
        void isDeterministicRegardlessOfInputOrder() {
            List<MatchRequest> requests = waiting(
                    Req.id(1).size(3).at(100),
                    Req.id(2).size(3).at(100),
                    Req.id(3).size(3).at(200),
                    Req.id(4).size(3).at(150),
                    Req.id(5).size(3).at(120),
                    Req.id(6).size(3).at(180));

            List<Party> expected = PartyMatcher.match(requests);

            List<MatchRequest> shuffled = new ArrayList<>(requests);
            for (int i = 0; i < shuffled.size(); i++) {
                shuffled.add(shuffled.removeFirst());
                assertThat(PartyMatcher.match(shuffled)).isEqualTo(expected);
            }
            assertThat(expected).hasSize(2);
        }
    }

    @Nested
    @DisplayName("조건별 분리")
    class Partitions {

        @Test
        @DisplayName("큐가 다르면 서로 다른 파티가 된다")
        void differentQueuesNeverMix() {
            List<Party> parties = PartyMatcher.match(waiting(
                    Req.id(1).queue(GameQueue.SOLO_DUO),
                    Req.id(2).queue(GameQueue.NORMAL),
                    Req.id(3).queue(GameQueue.SOLO_DUO),
                    Req.id(4).queue(GameQueue.NORMAL)));

            assertThat(parties).hasSize(2);
            assertThat(requestIds(parties.get(0))).containsExactly(1L, 3L);
            assertThat(requestIds(parties.get(1))).containsExactly(2L, 4L);
        }

        @Test
        @DisplayName("목표 인원이 다르면 서로 다른 파티가 된다")
        void differentTargetSizesNeverMix() {
            List<Party> parties = PartyMatcher.match(waiting(
                    Req.id(1).size(2),
                    Req.id(2).size(3),
                    Req.id(3).size(2),
                    Req.id(4).size(3),
                    Req.id(5).size(3)));

            assertThat(parties).hasSize(2);
            assertThat(parties.get(0).targetSize()).isEqualTo(2);
            assertThat(requestIds(parties.get(0))).containsExactly(1L, 3L);
            assertThat(parties.get(1).targetSize()).isEqualTo(3);
            assertThat(requestIds(parties.get(1))).containsExactly(2L, 4L, 5L);
        }

        @Test
        @DisplayName("플레이 목적이 다르면 서로 다른 파티가 된다")
        void differentPurposesNeverMix() {
            List<Party> parties = PartyMatcher.match(waiting(
                    Req.id(1).purpose(Purpose.RANK_UP),
                    Req.id(2).purpose(Purpose.CASUAL),
                    Req.id(3).purpose(Purpose.CASUAL),
                    Req.id(4).purpose(Purpose.RANK_UP)));

            assertThat(parties).hasSize(2);
            assertThat(requestIds(parties.get(0))).containsExactly(1L, 4L);
            assertThat(requestIds(parties.get(1))).containsExactly(2L, 3L);
        }
    }

    @Nested
    @DisplayName("포지션")
    class Positions {

        @Test
        @DisplayName("앉힐 자리가 없는 후보는 건너뛰고 다음 후보를 채택한다")
        void skipsCandidatesThatCannotBeSeated() {
            List<Party> parties = PartyMatcher.match(waiting(
                    Req.id(1).positions(Position.MID),
                    Req.id(2).positions(Position.MID),
                    Req.id(3).positions(Position.TOP)));

            assertThat(parties).hasSize(1);
            assertThat(requestIds(parties.getFirst())).containsExactly(1L, 3L);
        }

        @Test
        @DisplayName("자리를 잠그지 않으므로 이미 앉은 사람이 물러난다")
        void alreadySeatedMembersMakeRoom() {
            List<Party> parties = PartyMatcher.match(waiting(
                    Req.id(1).positions(Position.TOP, Position.MID),
                    Req.id(2).positions(Position.TOP)));

            assertThat(parties).hasSize(1);
            Party party = parties.getFirst();
            // 1번이 주 포지션 TOP을 내주고 MID로 물러났다.
            assertThat(party.positionOf(0)).isEqualTo(Position.MID);
            assertThat(party.positionOf(1)).isEqualTo(Position.TOP);
        }

        @Test
        @DisplayName("5인 파티는 다섯 포지션이 한 명씩 채워진다")
        void fivePlayerPartyFillsEveryPosition() {
            List<Party> parties = PartyMatcher.match(waiting(
                    Req.id(1).size(5).positions(Position.TOP),
                    Req.id(2).size(5).positions(Position.JUNGLE),
                    Req.id(3).size(5).positions(Position.MID),
                    Req.id(4).size(5).positions(Position.BOTTOM),
                    Req.id(5).size(5).positions(Position.SUPPORT)));

            assertThat(parties).hasSize(1);
            assertThat(parties.getFirst().assignment().positions())
                    .containsExactlyInAnyOrder(Position.values());
            assertThat(parties.getFirst().assignment().primaryCount()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("파티 정보")
    class PartyDetails {

        @Test
        @DisplayName("한 명이라도 음성 필수면 음성 파티다")
        void isAVoicePartyWhenAnyoneRequiresVoice() {
            List<Party> parties = PartyMatcher.match(waiting(
                    Req.id(1).voice(VoiceMode.POSSIBLE),
                    Req.id(2).voice(VoiceMode.REQUIRED)));

            assertThat(parties.getFirst().voiceParty()).isTrue();
        }

        @Test
        @DisplayName("아무도 음성 필수가 아니면 비음성 파티다")
        void isNotAVoicePartyWhenNobodyRequiresVoice() {
            List<Party> parties = PartyMatcher.match(waiting(
                    Req.id(1).voice(VoiceMode.POSSIBLE),
                    Req.id(2).voice(VoiceMode.NOT_USED)));

            assertThat(parties.getFirst().voiceParty()).isFalse();
        }

        @Test
        @DisplayName("큐·목표 인원·목적은 참가자 전원이 같다")
        void agreedConditionsComeFromTheMembers() {
            Party party = PartyMatcher.match(waiting(
                    Req.id(1).queue(GameQueue.FLEX).size(3).purpose(Purpose.LEARNING),
                    Req.id(2).queue(GameQueue.FLEX).size(3).purpose(Purpose.LEARNING),
                    Req.id(3).queue(GameQueue.FLEX).size(3).purpose(Purpose.LEARNING))).getFirst();

            assertThat(party.queue()).isEqualTo(GameQueue.FLEX);
            assertThat(party.targetSize()).isEqualTo(3);
            assertThat(party.purpose()).isEqualTo(Purpose.LEARNING);
        }
    }

    @Test
    @DisplayName("파티에 들지 못한 요청은 다음 실행에서 다시 후보가 된다")
    void leftoverRequestsStayAvailable() {
        List<MatchRequest> waiting = waiting(Req.id(1), Req.id(2), Req.id(3));

        Party first = PartyMatcher.match(waiting).getFirst();
        assertThat(requestIds(first)).containsExactly(1L, 2L);

        // 성립한 둘을 명단에서 지우고 새 요청 하나가 들어왔다고 하자.
        List<MatchRequest> next = waiting(Req.id(3), Req.id(4));

        assertThat(requestIds(PartyMatcher.match(next).getFirst())).containsExactly(3L, 4L);
    }
}

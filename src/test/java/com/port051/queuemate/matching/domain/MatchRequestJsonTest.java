package com.port051.queuemate.matching.domain;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 05-realtime-matching-contract 2장이 고정한 JSON 모양을 지키는지 확인한다.
 *
 * <p>부하 스크립트가 이 모양에 꽂히므로, 필드가 하나라도 이름이 바뀌거나 늘어나면
 * 스크립트가 조용히 다른 것을 재게 된다. 그래서 값뿐 아니라 필드 집합 자체를 검사한다.
 */
class MatchRequestJsonTest {

    /** 계약 문서에 실린 예시를 그대로 옮긴 것이다. 손대지 않는다. */
    private static final String CONTRACT_EXAMPLE = """
            {
              "requestId": 10482,
              "userId": 7,

              "queue": "SOLO_DUO",
              "targetSize": 5,
              "purpose": "RANK_UP",
              "playMinutes": 120,
              "voiceMode": "POSSIBLE",

              "primaryPosition": "MID",
              "subPositions": ["TOP", "JUNGLE"],

              "tierOrder": 14,
              "allowedTierMinOrder": 11,
              "allowedTierMaxOrder": 18,

              "requestedAt": 1755960000000
            }
            """;

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("계약 예시 JSON이 그대로 역직렬화된다")
    void deserializesContractExample() throws Exception {
        MatchRequest request = mapper.readValue(CONTRACT_EXAMPLE, MatchRequest.class);

        assertThat(request.requestId()).isEqualTo(10482L);
        assertThat(request.userId()).isEqualTo(7L);
        assertThat(request.queue()).isEqualTo(GameQueue.SOLO_DUO);
        assertThat(request.targetSize()).isEqualTo(5);
        assertThat(request.purpose()).isEqualTo(Purpose.RANK_UP);
        assertThat(request.playMinutes()).isEqualTo(120);
        assertThat(request.voiceMode()).isEqualTo(VoiceMode.POSSIBLE);
        assertThat(request.primaryPosition()).isEqualTo(Position.MID);
        assertThat(request.subPositions()).containsExactly(Position.TOP, Position.JUNGLE);
        assertThat(request.tierOrder()).isEqualTo(14);
        assertThat(request.allowedTierMinOrder()).isEqualTo(11);
        assertThat(request.allowedTierMaxOrder()).isEqualTo(18);
        assertThat(request.requestedAt()).isEqualTo(1_755_960_000_000L);
    }

    @Test
    @DisplayName("직렬화하면 계약이 정한 필드 열셋만 나온다")
    void serializesExactlyTheContractFields() throws Exception {
        MatchRequest request = mapper.readValue(CONTRACT_EXAMPLE, MatchRequest.class);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(request));

        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
                "requestId", "userId",
                "queue", "targetSize", "purpose", "playMinutes", "voiceMode",
                "primaryPosition", "subPositions",
                "tierOrder", "allowedTierMinOrder", "allowedTierMaxOrder",
                "requestedAt");
    }

    @Test
    @DisplayName("직렬화한 것을 다시 읽으면 같은 값이다")
    void roundTrips() throws Exception {
        MatchRequest original = mapper.readValue(CONTRACT_EXAMPLE, MatchRequest.class);

        MatchRequest restored = mapper.readValue(mapper.writeValueAsString(original), MatchRequest.class);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    @DisplayName("부 포지션이 없어도 읽힌다")
    void allowsMissingSubPositions() throws Exception {
        String json = CONTRACT_EXAMPLE.replace("\"subPositions\": [\"TOP\", \"JUNGLE\"],", "");

        MatchRequest request = mapper.readValue(json, MatchRequest.class);

        assertThat(request.subPositions()).isEmpty();
    }

    @Test
    @DisplayName("배정 가능 포지션은 주 포지션이 앞에 오고 중복이 없다")
    void assignablePositionsPutsPrimaryFirst() throws Exception {
        MatchRequest request = mapper.readValue(CONTRACT_EXAMPLE, MatchRequest.class);

        assertThat(request.assignablePositions())
                .containsExactly(Position.MID, Position.TOP, Position.JUNGLE);
    }

    @Test
    @DisplayName("부 포지션 목록은 밖에서 바꿀 수 없다")
    void subPositionsAreImmutable() {
        List<Position> mutable = new java.util.ArrayList<>(List.of(Position.TOP));
        MatchRequest request = new MatchRequest(
                1L, 1L, GameQueue.SOLO_DUO, 2, Purpose.RANK_UP, 60, VoiceMode.POSSIBLE,
                Position.MID, mutable, 14, 11, 18, 1L);

        mutable.add(Position.JUNGLE);

        assertThat(request.subPositions()).containsExactly(Position.TOP);
    }
}

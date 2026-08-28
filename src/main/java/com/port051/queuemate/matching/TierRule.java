package com.port051.queuemate.matching;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 게임 규칙상 함께 큐를 잡을 수 있는 티어인가. 01 3.5 · 3.6 · 4.1.
 *
 * <p><b>설정 데이터다.</b> 01 3.5가 "구체적인 허용 범위는 설정 데이터로 관리하며 코드에 고정하지 않는다"고
 * 못박았다. 라이엇이 시즌마다 이 규칙을 조정하는데 코드에 박으면 규칙이 바뀔 때마다 배포해야 하기 때문이다.
 * 그래서 밴드 표를 {@code application.yml}에서 읽는다.
 *
 * <p><b>티어 순서(tierOrder)의 뜻.</b> 계약(05)은 {@code tierOrder}를 정수로만 정의했다.
 * 이 구현은 아이언 IV = 1에서 시작해 디비전 단위로 1씩 올라가는 것으로 읽는다 —
 * 디비전이 넷인 티어가 일곱(아이언~다이아) 이라 1~28이 거기까지고,
 * 마스터 29 · 그랜드마스터 30 · 챌린저 31이 그 위다.
 *
 * <p>01 3.6에 따라 <b>마스터 이상은 솔로·듀오 랭크 요청 자체를 만들 수 없으므로</b>
 * 여기서는 그 경우를 방어적으로만 막는다. 걸러내는 자리는 요청 생성 검증이다.
 */
@ConfigurationProperties(prefix = "queuemate.tier")
public record TierRule(
        @DefaultValue("28") int highestRankedOrder,
        @DefaultValue List<Band> bands) {

    /**
     * 한 밴드. {@code maxOrder} 이하 구간에서는 두 사람의 tierOrder 차이가
     * {@code maxGap}을 넘으면 함께 큐를 잡을 수 없다.
     *
     * <p>01 3.5가 말한 세 가지 모양을 그대로 옮긴 것이다 —
     * 낮은 구간은 티어 단위로 여러 단계, 중간 구간은 한 티어 위아래,
     * 상위 구간은 디비전 단위. 밴드의 {@code maxGap} 숫자가 그 차이를 표현한다.
     */
    public record Band(int maxOrder, int maxGap) {}

    /**
     * 두 요청이 게임 규칙상 함께 플레이할 수 있는가.
     *
     * @return 티어 제약이 없는 큐(자유·일반)이면 항상 true
     */
    public boolean canQueueTogether(boolean tierRestricted, int tierOrderA, int tierOrderB) {
        if (!tierRestricted) return true;
        if (tierOrderA > highestRankedOrder || tierOrderB > highestRankedOrder) {
            return false; // 마스터 이상 — 01 3.6
        }
        int lower = Math.min(tierOrderA, tierOrderB);
        int gap = Math.abs(tierOrderA - tierOrderB);
        return gap <= maxGapAt(lower);
    }

    /** 낮은 쪽 티어가 속한 밴드의 허용 폭. 밴드는 {@code maxOrder} 오름차순으로 읽는다. */
    public int maxGapAt(int tierOrder) {
        return bands.stream()
                .sorted(java.util.Comparator.comparingInt(Band::maxOrder))
                .filter(band -> tierOrder <= band.maxOrder())
                .map(Band::maxGap)
                .findFirst()
                .orElse(0);
    }

    /** 이 티어로 솔로·듀오 랭크 요청을 만들 수 있는가. 01 3.6 — 마스터 이상은 불가. */
    public boolean canCreateSoloDuoRequest(int tierOrder) {
        return tierOrder >= 1 && tierOrder <= highestRankedOrder;
    }
}

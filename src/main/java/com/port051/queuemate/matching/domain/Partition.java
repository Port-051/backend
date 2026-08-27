package com.port051.queuemate.matching.domain;

import java.util.Comparator;
import java.util.List;

/**
 * 매칭이 실제로 일어날 수 있는 최소 단위. 01-functional-spec-mvp 4.1 · 3.4.
 *
 * <p>4.1은 큐가 다르거나 목표 인원이 다른 요청을 후보에서 제외한다. 두 조건 모두 정확 일치이므로
 * <b>서로 다른 조합의 요청은 어떤 경우에도 같은 파티가 되지 못한다.</b>
 * 그렇다면 애초에 한 줄에 세울 이유가 없다.
 *
 * <p>대기 명단을 이 단위로 나누면 두 가지를 얻는다.
 *
 * <ul>
 *   <li>매칭이 훑는 후보가 줄어든다. 섞여 있으면 큐가 다르다는 이유로 탈락시키는 비교를 매 틱 반복한다.
 *   <li><b>인스턴스마다 다른 조합을 맡을 수 있다.</b> 02-technical-spec-supplement 1.4가 말하는
 *       파티션 단일 라이터가 여기서 성립한다. 겹치지 않는 명단을 보면 배정 중 표시가 부딪힐 일도 없다.
 * </ul>
 *
 * <p>플레이 목적도 정확 일치 조건이라 더 잘게 나눌 수 있으나 넣지 않았다. 조합이 네 배로 늘어
 * 매 틱 읽어야 할 명단이 그만큼 많아지는데, 그 값어치가 있는지는 재 보고 정할 일이다.
 */
public record Partition(GameQueue queue, int targetSize) {

    public Partition {
        if (!queue.allows(targetSize)) {
            throw new IllegalArgumentException(
                    "%s 큐는 %d인 파티를 만들 수 없다".formatted(queue, targetSize));
        }
    }

    /** 요청이 속하는 조합. */
    public static Partition of(MatchRequest request) {
        return new Partition(request.queue(), request.targetSize());
    }

    /**
     * 존재할 수 있는 조합 전부. 매 틱 이 목록을 돈다.
     *
     * <p>순서를 고정해 두는 이유는 4.3의 결정성 때문이다. 조합을 도는 순서가 흔들리면
     * 두 조합에 걸친 요청이 어느 쪽에 먼저 잡히는지가 달라진다.
     */
    public static List<Partition> all() {
        return java.util.Arrays.stream(GameQueue.values())
                .flatMap(queue -> queue.targetSizes().stream()
                        .map(targetSize -> new Partition(queue, targetSize)))
                .sorted(Comparator.comparing((Partition p) -> p.queue().name())
                        .thenComparingInt(Partition::targetSize))
                .toList();
    }
}

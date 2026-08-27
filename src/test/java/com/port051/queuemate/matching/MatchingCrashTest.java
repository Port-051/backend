package com.port051.queuemate.matching;

import com.port051.queuemate.matching.domain.GameQueue;
import com.port051.queuemate.matching.domain.MatchRequest;
import com.port051.queuemate.matching.domain.Party;
import com.port051.queuemate.matching.domain.PartyMatcher;
import com.port051.queuemate.matching.domain.Position;
import com.port051.queuemate.matching.domain.Purpose;
import com.port051.queuemate.matching.domain.VoiceMode;
import com.port051.queuemate.matching.store.ClaimStore;
import com.port051.queuemate.matching.store.RequestStore;
import com.port051.queuemate.matching.store.WaitingList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인스턴스가 파티를 짜는 도중 멈추면 그 요청들이 어떻게 되는지 잰다.
 * 05-realtime-matching-contract 1장 · 3장.
 *
 * <p><b>{@link MatchingLoadTest}만으로는 claim을 고를 이유가 없다.</b> 그 표에서 명단 삭제와
 * claim은 둘 다 중복 배정 0으로 똑같이 보인다. 두 방식은 정상 동작에서 구분되지 않고,
 * <b>중간에 멈출 때</b> 갈린다. 그것이 이 측정이 있는 이유다.
 *
 * <h2>무엇을 "멈춤"으로 보는가</h2>
 *
 * <p>참가자를 선점하는 데는 성공했는데 <b>확정을 남기기 전에</b> 인스턴스가 사라지는 것이다.
 * 배포로 인한 재시작, OOM, 긴 GC 정지 — 버그가 아니라 정상 운영 중에 일어나는 일이다.
 * 선점과 확정 사이는 아무리 짧아도 0이 아니므로 언젠가 여기서 멈춘다.
 *
 * <p>실제로 스레드를 죽이지는 않는다. <b>선점 직후 확정을 건너뛰고 다음 사이클로 넘어가면</b>
 * Redis에 남는 상태가 프로세스가 죽은 것과 같다. 죽은 인스턴스가 곧바로 재시작한 것으로 보면 된다.
 * 죽는 시점을 확률이 아니라 {@link #CRASH_EVERY_TAKES}번째 선점으로 고정해 두어
 * 같은 조건에서 같은 횟수가 나오게 한다.
 *
 * <h2>무엇을 세는가</h2>
 *
 * <p><b>증발</b> — 유입됐는데 확정 파티에도 없고 대기 명단에도 없는 요청이다.
 * 사용자 입장에서는 신청을 넣었는데 파티도 안 오고 취소도 안 된 채 대기 화면에 남는 상태다.
 * 서버에는 아무 흔적도 남지 않으므로, 저장소를 직접 대조하는 것 말고는 알아낼 방법이 없다.
 *
 * <p>측정 조건은 {@link MatchingLoadTest}와 다르다. 유입을 <b>미리 정해진 개수만큼 넣고
 * 시작</b>해서, 명단이 빌 때까지 돌린다. 증발한 건수를 셈에서 유입이 흔들리면 안 되기 때문이다.
 */
@SpringBootTest(properties = "queuemate.matching.claim-ttl=" + MatchingCrashTest.CLAIM_TTL)
@Testcontainers
class MatchingCrashTest {

    @Container
    @ServiceConnection
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    /**
     * 배정 중 표시의 유지 시간. 기본값 3초를 그대로 쓰면 회수를 기다리느라 측정이 길어진다.
     *
     * <p>줄여도 결론은 같다. 여기서 보는 것은 "얼마나 빨리 회수되는가"가 아니라
     * <b>회수되는 길이 있는가</b>이기 때문이다.
     */
    static final long CLAIM_TTL_MILLIS = 100;

    /** {@code @SpringBootTest}에 넘길 형태. 상수식이어야 애노테이션에 쓸 수 있다. */
    static final String CLAIM_TTL = CLAIM_TTL_MILLIS + "ms";

    /** 인스턴스 수. 멈추는 쪽과 살아남는 쪽이 모두 있어야 회수가 관측된다. */
    private static final int INSTANCES = 4;

    /** 이 번째 선점마다 멈춘다. 낮출수록 멈춤이 잦아진다. */
    private static final int CRASH_EVERY_TAKES = 4;

    /** 미리 넣어 둘 요청 수. 2인 파티이므로 정상이라면 정확히 절반의 파티가 나온다. */
    private static final int ARRIVALS = 400;

    /**
     * 명단이 빌 때까지 기다리는 한도.
     *
     * <p>claim 쪽은 멈춘 선점마다 유지 시간을 한 번씩 기다려야 하므로 끝으로 갈수록 느려진다.
     * 한도를 넘겨도 실패로 보지 않는다 — 남은 것은 <b>명단에 남아 있는 것</b>이지 잃어버린 것이 아니고,
     * 표의 "명단잔여"에 그대로 드러난다.
     */
    private static final Duration DRAIN_LIMIT = Duration.ofSeconds(20);

    /** 명단이 비었는지 확인하는 간격. */
    private static final Duration DRAIN_POLL = Duration.ofMillis(20);

    private static final Path OUTPUT =
            Path.of(System.getProperty("matching.crash.out", "measurements/matching-crash.csv"));

    @Autowired
    WaitingList waiting;

    @Autowired
    RequestStore requests;

    @Autowired
    ClaimStore claims;

    @Autowired
    StringRedisTemplate strings;

    /** 선점 방식. {@link MatchingLoadTest}의 것과 같되, 제어가 없는 경우는 여기서 볼 것이 없어 뺀다. */
    private enum Guard {

        /** 명단에서 먼저 지운다. 지운 뒤 멈추면 되돌릴 주체가 없다. */
        REMOVE("삭제"),

        /** 명단에 둔 채 표시를 건다. 멈춰도 유지 시간이 지나면 풀린다. */
        CLAIM("claim");

        private final String label;

        Guard(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    @Test
    @DisplayName("선점한 뒤 멈추면 명단 삭제는 요청을 잃고 claim은 회수한다")
    void measureCrashRecovery() throws Exception {
        List<Measurement> results = new ArrayList<>();
        for (Guard guard : Guard.values()) {
            results.add(measure(guard));
        }

        printTable(results);
        writeCsv(results);

        Measurement remove = results.get(0);
        Measurement claim = results.get(1);

        assertThat(remove.crashes())
                .as("멈춤이 한 번도 일어나지 않아 비교가 성립하지 않는다")
                .isPositive();
        assertThat(claim.crashes())
                .as("멈춤이 한 번도 일어나지 않아 비교가 성립하지 않는다")
                .isPositive();

        assertThat(remove.evaporated())
                .as("명단에서 지운 뒤 멈췄는데 잃어버린 요청이 없다 — 시나리오가 재현되지 않았다")
                .isPositive();

        assertThat(claim.evaporated())
                .as("claim을 걸었는데 요청을 잃었다. 유지 시간이 지나면 명단에 그대로 남아 있어야 한다")
                .isZero();

        assertThat(claim.violatedRequests())
                .as("회수된 요청이 두 파티에 들어갔다")
                .isZero();
        assertThat(remove.violatedRequests())
                .as("명단에서 지웠는데 중복 배정이 났다")
                .isZero();
    }

    /** 한 방식을 잰다. 요청을 전부 넣어 두고, 명단이 빌 때까지 인스턴스 여럿이 루프를 돈다. */
    private Measurement measure(Guard guard) throws Exception {
        strings.getConnectionFactory().getConnection().serverCommands().flushAll();

        for (long requestId = 1; requestId <= ARRIVALS; requestId++) {
            MatchRequest request = request(requestId);
            requests.save(request);
            waiting.add(request);
        }

        AtomicBoolean running = new AtomicBoolean(true);
        ConcurrentLinkedQueue<List<Long>> confirmed = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(INSTANCES);
        try {
            List<Future<Long>> loops = new ArrayList<>();
            for (int i = 0; i < INSTANCES; i++) {
                loops.add(pool.submit(() -> {
                    start.await();
                    return loop(running, guard, confirmed);
                }));
            }

            start.countDown();
            awaitDrain();
            running.set(false);

            long crashes = 0;
            for (Future<Long> instance : loops) {
                crashes += instance.get();
            }

            return summarize(guard, confirmed, crashes);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 매칭 루프. {@link MatchingLoadTest#loop}와 같은 절차인데 <b>선점 직후 멈추는 경로</b>가 있다.
     *
     * @return 이 인스턴스가 멈춘 횟수
     */
    private long loop(AtomicBoolean running, Guard guard, ConcurrentLinkedQueue<List<Long>> confirmed) {
        long takes = 0;
        long crashes = 0;

        while (running.get()) {
            List<MatchRequest> snapshot = waiting.requestIds().stream()
                    .map(requests::find)
                    .flatMap(Optional::stream)
                    .toList();

            for (Party party : PartyMatcher.match(snapshot)) {
                List<Long> memberIds = memberIdsOf(party);
                if (!take(guard, memberIds)) {
                    // 남이 먼저 가져갔다. claim이면 아직 유지 시간이 남은 것일 수도 있다.
                    continue;
                }

                if (++takes % CRASH_EVERY_TAKES == 0) {
                    // 여기서 인스턴스가 사라진다. 선점은 이미 Redis에 남았고 확정은 남지 않는다.
                    // REMOVE는 명단에서 빠진 채로, CLAIM은 표시가 걸린 채로 남는다.
                    crashes++;
                    continue;
                }

                if (guard != Guard.REMOVE) {
                    memberIds.forEach(waiting::remove);
                }
                confirmed.add(memberIds);
            }
        }
        return crashes;
    }

    /** 3단계 — 참가자 전원을 선점한다. 두 방식이 갈리는 곳은 여기 한 줄뿐이다. */
    private boolean take(Guard guard, List<Long> memberIds) {
        return switch (guard) {
            case REMOVE -> waiting.removeAll(memberIds);
            case CLAIM -> claims.claimAll(memberIds).isPresent();
        };
    }

    /**
     * 명단이 빌 때까지 기다린다.
     *
     * <p>claim이면 멈춘 인스턴스가 잡아 둔 것도 유지 시간이 지나면 다시 후보가 되므로 결국 빈다.
     * 명단에서 지우는 방식이면 잃어버린 요청이 명단에 없으니 <b>더 빨리 빈다</b> — 빈 것이
     * 다 처리했다는 뜻이 아니라는 게 요점이라, 비었다고 성공으로 읽지 않는다.
     */
    private void awaitDrain() {
        long deadline = System.nanoTime() + DRAIN_LIMIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (waiting.size() == 0) {
                // 유지 시간이 남은 표시가 회수되고 그것까지 처리될 여지를 한 번 더 준다.
                LockSupport.parkNanos(claims.ttl().toNanos() * 2);
                if (waiting.size() == 0) {
                    return;
                }
            }
            LockSupport.parkNanos(DRAIN_POLL.toNanos());
        }
    }

    /** 유입된 요청이 어디로 갔는지 대조한다. */
    private Measurement summarize(Guard guard, ConcurrentLinkedQueue<List<Long>> confirmed, long crashes) {
        Map<Long, Integer> partiesPerRequest = new HashMap<>();
        for (List<Long> party : confirmed) {
            for (long requestId : party) {
                partiesPerRequest.merge(requestId, 1, Integer::sum);
            }
        }
        long violated = partiesPerRequest.values().stream().filter(count -> count > 1).count();

        Set<Long> stillWaiting = new HashSet<>(waiting.requestIds());
        Set<Long> accountedFor = new HashSet<>(partiesPerRequest.keySet());
        accountedFor.addAll(stillWaiting);

        // 유입됐는데 확정에도 없고 명단에도 없는 것. 사용자는 영원히 대기 화면에 남는다.
        long evaporated = ARRIVALS - accountedFor.size();

        return new Measurement(
                guard,
                ARRIVALS,
                confirmed.size(),
                partiesPerRequest.size(),
                stillWaiting.size(),
                evaporated,
                (double) evaporated / ARRIVALS,
                violated,
                crashes);
    }

    private void printTable(List<Measurement> results) {
        System.out.println();
        System.out.println("=== 선점 뒤 멈춤 · 요청 증발 (유지 시간 " + CLAIM_TTL
                + ", 인스턴스 " + INSTANCES + ", " + CRASH_EVERY_TAKES + "번째 선점마다 멈춤) ===");
        System.out.printf("%-8s %8s %8s %10s %10s %8s %8s %8s %8s%n",
                "선점", "유입", "멈춤", "확정파티", "확정요청", "명단잔여", "증발", "증발률", "중복배정");
        for (Measurement result : results) {
            System.out.printf("%-9s %8d %8d %10d %10d %8d %8d %7.1f%% %8d%n",
                    result.guard().label(),
                    result.arrivals(),
                    result.crashes(),
                    result.partiesConfirmed(),
                    result.requestsConfirmed(),
                    result.stillWaiting(),
                    result.evaporated(),
                    result.evaporationRate() * 100,
                    result.violatedRequests());
        }
        System.out.println();
    }

    private void writeCsv(List<Measurement> results) throws IOException {
        Files.createDirectories(OUTPUT.toAbsolutePath().getParent());

        StringBuilder csv = new StringBuilder(
                "guard,claimTtl,instances,crashEveryTakes,arrivals,crashes,partiesConfirmed,"
                        + "requestsConfirmed,stillWaiting,evaporated,evaporationRate,violatedRequests\n");
        for (Measurement result : results) {
            csv.append("%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%.4f,%d%n".formatted(
                    result.guard().name().toLowerCase(),
                    CLAIM_TTL,
                    INSTANCES,
                    CRASH_EVERY_TAKES,
                    result.arrivals(),
                    result.crashes(),
                    result.partiesConfirmed(),
                    result.requestsConfirmed(),
                    result.stillWaiting(),
                    result.evaporated(),
                    result.evaporationRate(),
                    result.violatedRequests()));
        }
        Files.writeString(OUTPUT, csv.toString());
        System.out.println("측정값: " + OUTPUT.toAbsolutePath());
    }

    /** 한 방식의 측정값. */
    private record Measurement(
            Guard guard,
            int arrivals,
            int partiesConfirmed,
            int requestsConfirmed,
            int stillWaiting,
            long evaporated,
            double evaporationRate,
            long violatedRequests,
            long crashes) {
    }

    private static List<Long> memberIdsOf(Party party) {
        return party.members().stream().map(MatchRequest::requestId).toList();
    }

    /** 둘이 만나면 바로 파티가 되는 요청. 조건은 전부 서로 맞다. */
    private static MatchRequest request(long requestId) {
        return new MatchRequest(
                requestId, requestId,
                GameQueue.SOLO_DUO, 2, Purpose.RANK_UP, 60, VoiceMode.POSSIBLE,
                Position.MID, List.of(Position.TOP, Position.JUNGLE, Position.BOTTOM, Position.SUPPORT),
                14, 1, 30,
                requestId);
    }
}

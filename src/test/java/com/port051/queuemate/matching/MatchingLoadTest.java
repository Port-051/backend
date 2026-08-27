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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 선점 방식 세 가지가 중복 배정을 얼마나 막는지 부하 아래에서 잰다. 05-realtime-matching-contract 3장.
 *
 * <p><b>여기서는 결론이 나지 않는다.</b> 제어를 하느냐 마느냐는 갈리지만, 명단에서 지우는 것과
 * 표시를 거는 것은 이 표에서 <b>둘 다 위반 0</b>으로 똑같이 보인다. 두 방식을 가르는 것은
 * 정상 동작이 아니라 인스턴스가 중간에 멈추는 경우이고, 그것은 {@link MatchingCrashTest}가 잰다.
 * 두 표를 나란히 놓아야 왜 claim인지가 나온다.
 *
 * <p>{@link MatchingRaceTest}는 barrier로 틈을 강제로 벌려 "논리적으로 깨진다"를 보였다.
 * 여기서는 <b>아무것도 강제하지 않는다.</b> 요청이 계속 들어오는 동안 인스턴스 여럿이 각자
 * 매칭 루프를 자연스러운 속도로 돌리고, 그 결과 중복 배정이 실제로 몇 건 나는지 센다.
 * 억지로 맞춰야 나는 것과 그냥 돌려도 나는 것은 다른 이야기다.
 *
 * <p><b>위반을 셀 수 있는 이유가 있다.</b> 아직 확정 파티를 저장하는 곳이 없어서 서버 바깥에서는
 * 셀 수가 없다. 하지만 여기서는 하네스가 곧 인스턴스라, 각자 확정한 파티를 손에 들고 있다.
 * 그것을 모아 한 요청이 두 파티에 들어갔는지 보면 된다.
 *
 * <p><b>이것은 분산 부하 시험이 아니다.</b> 인스턴스는 한 JVM 안의 스레드이고 Redis만 진짜다.
 * 네트워크 지연도, 인스턴스별 GC도, 실제 요청 분포도 없다. 여기서 재는 것은 처리량의 절대값이
 * 아니라 <b>같은 조건에서 선점 방식이 만드는 차이</b>다. 절대값은 API가 선 뒤 k6로 잰다
 * (04-tech-stack 4장).
 */
@SpringBootTest
@Testcontainers
class MatchingLoadTest {

    @Container
    @ServiceConnection
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    /** 인스턴스를 늘려가며 잰다. 늘어날수록 같은 명단을 동시에 읽을 확률이 올라간다. */
    private static final int[] INSTANCE_COUNTS = {2, 4, 8};

    /**
     * 사이클 주기. 두 가지로 잰다.
     *
     * <p>{@code 0}은 쉼 없이 도는 것이다. 겹칠 틈이 최대라 <b>상한</b>을 본다.
     * {@code 10}은 실제 매칭 루프처럼 주기를 두고 도는 것이다. 여기서 나오는 값이
     * 운영에서 볼 모양에 가깝다.
     */
    private static final long[] CYCLE_INTERVALS_MILLIS = {0, 10};

    /** 한 조건을 재는 시간. 짧게 두어 CI에서 부담이 되지 않게 한다. */
    private static final Duration RUN = Duration.ofMillis(1500);

    /** 요청 유입 간격. 명단이 비지 않을 정도로만 계속 밀어 넣는다. */
    private static final long ARRIVAL_INTERVAL_NANOS = 600_000;

    /** 사이클 위상을 흩는 씨드. 고정해 두어야 같은 부하가 재현된다. */
    private static final long PHASE_SEED = 20260827;

    /**
     * 측정값을 떨굴 곳. {@code build/} 밖에 둔다.
     *
     * <p>테스트가 {@code build/} 안에 디렉터리를 만들면 Gradle이 자기 작업 산출물을
     * 감시하던 것과 부딪혀 테스트가 통과해도 빌드가 깨진다.
     */
    private static final Path OUTPUT =
            Path.of(System.getProperty("matching.load.out", "measurements/matching-load.csv"));

    @Autowired
    WaitingList waiting;

    @Autowired
    RequestStore requests;

    @Autowired
    ClaimStore claims;

    @Autowired
    StringRedisTemplate strings;

    /**
     * 파티를 확정하기 전에 참가자를 <b>선점하는 방식</b>. 이 축이 이 측정의 비교 대상이다.
     *
     * <p>{@link #REMOVE}가 여기 있는 이유는 그것이 먼저 떠오르는 답이기 때문이다. 명단에서
     * 빼 버리면 늦게 온 인스턴스는 빈손이 되므로 <b>중복 배정은 이것으로도 막힌다.</b>
     * 두 방식이 갈리는 곳은 여기가 아니라 인스턴스가 중간에 멈추는 경우이고,
     * 그것은 {@link MatchingCrashTest}에서 잰다.
     */
    private enum Guard {

        /** 제어 없음. 찾으면 바로 확정한다. */
        NONE("없음"),

        /** 명단에서 먼저 지운다. 전원을 지우지 못하면 남이 가져간 것이다. */
        REMOVE("삭제"),

        /** 명단에 둔 채 배정 중 표시를 건다. 계약 3장이 정한 방식이다. */
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
    @DisplayName("인스턴스를 늘려가며 선점 방식별로 중복 배정을 잰다")
    void measureGuardsUnderLoad() throws Exception {
        List<Measurement> results = new ArrayList<>();
        for (long interval : CYCLE_INTERVALS_MILLIS) {
            for (int instances : INSTANCE_COUNTS) {
                for (Guard guard : Guard.values()) {
                    results.add(measure(instances, guard, interval));
                }
            }
        }

        printTable(results);
        writeCsv(results);

        for (Measurement result : results) {
            if (result.guard() == Guard.NONE) {
                continue;
            }
            assertThat(result.violatedRequests())
                    .as("선점했는데 중복 배정이 났다 — %s, 인스턴스 %d개, 주기 %dms"
                            .formatted(result.guard().label(), result.instances(), result.cycleIntervalMillis()))
                    .isZero();
        }
    }

    /** 한 조건을 잰다. 요청을 계속 흘려 넣으면서 인스턴스 여럿이 각자 루프를 돈다. */
    private Measurement measure(int instances, Guard guard, long cycleIntervalMillis) throws Exception {
        strings.getConnectionFactory().getConnection().serverCommands().flushAll();

        AtomicLong nextRequestId = new AtomicLong();
        AtomicBoolean running = new AtomicBoolean(true);
        ConcurrentLinkedQueue<List<Long>> confirmed = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(instances + 1);
        try {
            Future<?> arrivals = pool.submit(() -> {
                start.await();
                // 정해진 시각표대로 넣는다. 간격만큼 재우면 조건마다 유입량이 달라져
                // 행끼리 비교가 되지 않는다.
                long began = System.nanoTime();
                for (long nth = 0; running.get(); nth++) {
                    long due = began + nth * ARRIVAL_INTERVAL_NANOS;
                    long wait = due - System.nanoTime();
                    if (wait > 0) {
                        LockSupport.parkNanos(wait);
                    }
                    MatchRequest request = request(nextRequestId.incrementAndGet());
                    requests.save(request);
                    waiting.add(request);
                }
                return null;
            });

            List<Future<Instance>> loops = new ArrayList<>();
            for (int i = 0; i < instances; i++) {
                // 인스턴스마다 사이클 위상을 어긋나게 둔다. 전부 같은 순간에 시작하면
                // 주기가 맞물려 매번 겹치는데, 따로 뜬 인스턴스들이 그럴 이유가 없다.
                // 씨드를 고정해 같은 부하가 재현되게 한다(04-tech-stack 4.2).
                long phaseOffsetNanos = cycleIntervalMillis == 0
                        ? 0
                        : (long) (new Random(PHASE_SEED + i).nextDouble() * cycleIntervalMillis * 1_000_000);
                loops.add(pool.submit(() -> {
                    start.await();
                    LockSupport.parkNanos(phaseOffsetNanos);
                    return loop(running, guard, cycleIntervalMillis, confirmed);
                }));
            }

            start.countDown();
            long began = System.nanoTime();
            LockSupport.parkNanos(RUN.toNanos());
            running.set(false);

            Instance total = Instance.EMPTY;
            for (Future<Instance> instance : loops) {
                total = total.plus(instance.get());
            }
            arrivals.get();
            long elapsedNanos = System.nanoTime() - began;

            return summarize(instances, guard, cycleIntervalMillis, nextRequestId.get(),
                    confirmed, total, elapsedNanos);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 매칭 루프. 계약 3장 그대로이고, 3단계에서 무엇으로 선점하는지만 {@link Guard}가 정한다.
     *
     * <p>{@link MatchingRaceTest#cycle}과 같은 절차인데 barrier가 없다. 인스턴스들이
     * 서로를 기다리지 않고 각자 최대 속도로 돌므로, 겹치는 순간은 우연히 생긴다.
     */
    private Instance loop(AtomicBoolean running, Guard guard, long cycleIntervalMillis,
                          ConcurrentLinkedQueue<List<Long>> confirmed) {
        List<Long> cycleNanos = new ArrayList<>();
        long blockedParties = 0;

        while (running.get()) {
            long cycleBegan = System.nanoTime();

            // 1·2단계 — 명단을 읽고 메모를 본다.
            List<MatchRequest> snapshot = waiting.requestIds().stream()
                    .map(requests::find)
                    .flatMap(Optional::stream)
                    .toList();

            for (Party party : PartyMatcher.match(snapshot)) {
                List<Long> memberIds = memberIdsOf(party);
                if (!take(guard, memberIds)) {
                    // 3단계 — 누가 먼저 가져갔다. 이번 사이클은 넘긴다.
                    blockedParties++;
                    continue;
                }
                // 4단계 — 확정. REMOVE는 선점이 곧 삭제라 여기서 또 지울 것이 없다.
                if (guard != Guard.REMOVE) {
                    memberIds.forEach(waiting::remove);
                }
                confirmed.add(memberIds);
            }
            cycleNanos.add(System.nanoTime() - cycleBegan);

            if (cycleIntervalMillis > 0) {
                LockSupport.parkNanos(cycleIntervalMillis * 1_000_000);
            }
        }
        return new Instance(cycleNanos, blockedParties);
    }

    /**
     * 3단계 — 참가자 전원을 선점한다. 성공하면 이 파티는 내 것이다.
     *
     * <p>세 방식이 갈리는 곳은 여기 한 군데뿐이다. 나머지 절차는 전부 같게 두어야
     * 표의 차이를 선점 방식 탓으로 읽을 수 있다.
     */
    private boolean take(Guard guard, List<Long> memberIds) {
        return switch (guard) {
            case NONE -> true;
            case REMOVE -> waiting.removeAll(memberIds);
            case CLAIM -> claims.claimAll(memberIds).isPresent();
        };
    }

    /** 한 요청이 두 파티에 들어갔는지 센다. 불변식 INV-3이 묻는 그것이다. */
    private static Measurement summarize(int instances, Guard guard, long cycleIntervalMillis,
                                         long arrivals, ConcurrentLinkedQueue<List<Long>> confirmed,
                                         Instance total, long elapsedNanos) {
        Map<Long, Integer> partiesPerRequest = new HashMap<>();
        for (List<Long> party : confirmed) {
            for (long requestId : party) {
                partiesPerRequest.merge(requestId, 1, Integer::sum);
            }
        }
        long violated = partiesPerRequest.values().stream().filter(count -> count > 1).count();

        List<Long> sorted = new ArrayList<>(total.cycleNanos());
        sorted.sort(null);
        double seconds = elapsedNanos / 1_000_000_000.0;

        return new Measurement(
                instances,
                guard,
                cycleIntervalMillis,
                arrivals,
                confirmed.size(),
                violated,
                partiesPerRequest.isEmpty() ? 0 : (double) violated / partiesPerRequest.size(),
                sorted.size(),
                total.blockedParties(),
                percentileMicros(sorted, 0.50),
                percentileMicros(sorted, 0.95),
                confirmed.size() / seconds);
    }

    private static double percentileMicros(List<Long> sortedNanos, double percentile) {
        if (sortedNanos.isEmpty()) {
            return 0;
        }
        int index = (int) Math.min(sortedNanos.size() - 1L, Math.round(percentile * (sortedNanos.size() - 1)));
        return sortedNanos.get(index) / 1_000.0;
    }

    private void printTable(List<Measurement> results) {
        System.out.println();
        System.out.println("=== 선점 방식 비교 · 중복 배정 (인스턴스 = 한 JVM 안의 스레드, Redis만 실제) ===");
        System.out.printf("%-8s %-6s %-8s %8s %8s %8s %8s %8s %8s %10s %10s%n",
                "주기", "인스턴스", "선점", "유입", "확정파티", "위반", "위반율", "사이클", "막힌파티", "p50(us)", "파티/초");
        for (Measurement result : results) {
            System.out.printf("%-9s %-9d %-8s %8d %8d %8d %7.1f%% %8d %8d %10.0f %10.1f%n",
                    result.cycleIntervalMillis() == 0 ? "쉼없이" : result.cycleIntervalMillis() + "ms",
                    result.instances(),
                    result.guard().label(),
                    result.arrivals(),
                    result.partiesConfirmed(),
                    result.violatedRequests(),
                    result.violationRate() * 100,
                    result.cycles(),
                    result.blockedParties(),
                    result.cycleP50Micros(),
                    result.partiesPerSecond());
        }
        System.out.println();
    }

    /** 차트로 그릴 수 있게 떨군다. */
    private void writeCsv(List<Measurement> results) throws IOException {
        Files.createDirectories(OUTPUT.toAbsolutePath().getParent());

        StringBuilder csv = new StringBuilder(
                "cycleIntervalMillis,instances,guard,arrivals,partiesConfirmed,violatedRequests,violationRate,"
                        + "cycles,blockedParties,cycleP50Micros,cycleP95Micros,partiesPerSecond\n");
        for (Measurement result : results) {
            csv.append("%d,%d,%s,%d,%d,%d,%.4f,%d,%d,%.1f,%.1f,%.1f%n".formatted(
                    result.cycleIntervalMillis(),
                    result.instances(),
                    result.guard().name().toLowerCase(),
                    result.arrivals(),
                    result.partiesConfirmed(),
                    result.violatedRequests(),
                    result.violationRate(),
                    result.cycles(),
                    result.blockedParties(),
                    result.cycleP50Micros(),
                    result.cycleP95Micros(),
                    result.partiesPerSecond()));
        }
        Files.writeString(OUTPUT, csv.toString());
        System.out.println("측정값: " + OUTPUT.toAbsolutePath());
    }

    /** 인스턴스 하나가 돌린 결과. */
    private record Instance(List<Long> cycleNanos, long blockedParties) {

        static final Instance EMPTY = new Instance(List.of(), 0);

        Instance plus(Instance other) {
            List<Long> merged = new ArrayList<>(cycleNanos);
            merged.addAll(other.cycleNanos());
            return new Instance(merged, blockedParties + other.blockedParties());
        }
    }

    /** 한 조건의 측정값. */
    private record Measurement(
            int instances,
            Guard guard,
            long cycleIntervalMillis,
            long arrivals,
            int partiesConfirmed,
            long violatedRequests,
            double violationRate,
            int cycles,
            long blockedParties,
            double cycleP50Micros,
            double cycleP95Micros,
            double partiesPerSecond) {
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

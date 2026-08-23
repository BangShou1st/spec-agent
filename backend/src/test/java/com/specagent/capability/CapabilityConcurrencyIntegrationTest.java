package com.specagent.capability;

import com.specagent.common.Ids;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency and recovery semantics of the capability invocation log,
 * verified against real PostgreSQL: the unique index on invocation_key is
 * the final arbiter of execution ownership, so concurrent invocations of one
 * key execute the adapter at most once without leaking constraint failures,
 * and recorded RUNNING/SUCCEEDED/FAILED states are never confused with each
 * other on retry.
 */
@SpringBootTest
@ActiveProfiles("test")
class CapabilityConcurrencyIntegrationTest {

    private static final String KEY_PREFIX = "conc-test-";

    @TestConfiguration
    static class CountingCapabilityConfig {

        private static final AtomicInteger EXECUTIONS = new AtomicInteger();
        private static final AtomicInteger FAILURES = new AtomicInteger();

        @Bean
        CapabilityAdapter countingTestCapability() {
            return new CapabilityAdapter() {
                @Override
                public CapabilityDescriptor descriptor() {
                    return new CapabilityDescriptor("test.counting", "1",
                            "counting test capability", Map.of(), Map.of(),
                            false, SideEffectClass.LOCAL_DURABLE, List.of(), List.of());
                }

                @Override
                public CapabilityResult invoke(CapabilityInvocation invocation) {
                    EXECUTIONS.incrementAndGet();
                    if (FAILURES.get() > 0 && invocation.arguments().get("mode") != null) {
                        throw new IllegalStateException("simulated adapter crash");
                    }
                    return new CapabilityResult(invocation.invocationId(),
                            invocation.invocationKey(), invocation.capabilityId(),
                            CapabilityResult.Status.SUCCEEDED,
                            Map.of("ok", true), List.of(), Map.of(), List.of());
                }
            };
        }
    }

    @Autowired private ProjectService projectService;
    @Autowired private CapabilityRuntime capabilityRuntime;
    @Autowired private CapabilityInvocationRepository invocationRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Project project;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("能力并发测试-" + UUID.randomUUID());
        CountingCapabilityConfig.EXECUTIONS.set(0);
        CountingCapabilityConfig.FAILURES.set(0);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update(
                "DELETE FROM capability_invocations WHERE invocation_key LIKE ?",
                KEY_PREFIX + "%");
    }

    @Test
    void concurrentInvokeWithSameKeyExecutesAdapterExactlyOnceWithoutConstraintFailure()
            throws Exception {
        int workers = 6;
        String key = KEY_PREFIX + Ids.random();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CyclicBarrier startLine = new CyclicBarrier(workers);
        try {
            List<Future<CapabilityResult>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit((Callable<CapabilityResult>) () -> {
                    startLine.await(10, TimeUnit.SECONDS);
                    return capabilityRuntime.invoke(key, "test.counting",
                            project.id(), null, Map.of());
                }));
            }

            List<CapabilityResult> results = new ArrayList<>();
            for (Future<CapabilityResult> future : futures) {
                // Losing racers must read back the winner's recorded result —
                // a unique-constraint error leaking here is a contract break.
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            long succeeded = results.stream()
                    .filter(r -> r.status() == CapabilityResult.Status.SUCCEEDED).count();
            long replayed = results.stream()
                    .filter(r -> r.status() == CapabilityResult.Status.REPLAYED).count();
            assertThat(succeeded).isEqualTo(1);
            assertThat(replayed).isEqualTo(workers - 1);
            assertThat(CountingCapabilityConfig.EXECUTIONS.get()).isEqualTo(1);
            assertThat(invocationRepository.findByInvocationKey(key)).isPresent();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void succeededReplayNeverReExecutesAdapter() {
        String key = KEY_PREFIX + Ids.random();

        CapabilityResult first = capabilityRuntime.invoke(key, "test.counting",
                project.id(), null, Map.of());
        CapabilityResult replay = capabilityRuntime.invoke(key, "test.counting",
                project.id(), null, Map.of());

        assertThat(first.status()).isEqualTo(CapabilityResult.Status.SUCCEEDED);
        assertThat(replay.status()).isEqualTo(CapabilityResult.Status.REPLAYED);
        assertThat(replay.content().get("ok")).isEqualTo(true);
        assertThat(CountingCapabilityConfig.EXECUTIONS.get()).isEqualTo(1);
    }

    @Test
    void failedReplayReturnsTheRecordedFailureWithoutReExecution() {
        String key = KEY_PREFIX + Ids.random();
        CountingCapabilityConfig.FAILURES.set(1);

        CapabilityResult first = capabilityRuntime.invoke(key, "test.counting",
                project.id(), null, Map.of("mode", "fail"));
        CapabilityResult retry = capabilityRuntime.invoke(key, "test.counting",
                project.id(), null, Map.of("mode", "fail"));

        assertThat(first.status()).isEqualTo(CapabilityResult.Status.FAILED);
        assertThat(String.valueOf(first.content().get("reason"))).contains("failed");
        // The recorded failure is returned verbatim; the adapter is not
        // invoked again and no second external attempt happens.
        assertThat(retry.status()).isEqualTo(CapabilityResult.Status.FAILED);
        assertThat(retry.content()).isEqualTo(first.content());
        assertThat(CountingCapabilityConfig.EXECUTIONS.get()).isEqualTo(1);
        assertThat(invocationRepository.findByInvocationKey(key).orElseThrow().status())
                .isEqualTo(CapabilityResult.Status.FAILED);
    }

    @Test
    void runningInvocationIsNeitherReplayedAsSuccessNorReExecuted() {
        String key = KEY_PREFIX + Ids.random();
        // Simulate a concurrent owner / crash-window row: claimed but never
        // completed.
        invocationRepository.claim(new CapabilityInvocation(Ids.random(), key,
                "test.counting", project.id(), null, Map.of()));

        CapabilityResult observed = capabilityRuntime.invoke(key, "test.counting",
                project.id(), null, Map.of());

        assertThat(observed.status())
                .as("a RUNNING invocation must surface as a real in-progress state")
                .isEqualTo(CapabilityResult.Status.IN_PROGRESS);
        assertThat(String.valueOf(observed.content().get("reason"))).isNotBlank();
        assertThat(CountingCapabilityConfig.EXECUTIONS.get())
                .as("the adapter must not run behind an unfinished invocation")
                .isZero();
    }

    @Test
    void unknownCapabilityFailureIsRepeatableReadable() {
        String key = KEY_PREFIX + Ids.random();

        CapabilityResult first = capabilityRuntime.invoke(key, "no.such.capability",
                project.id(), null, Map.of());
        CapabilityResult again = capabilityRuntime.invoke(key, "no.such.capability",
                project.id(), null, Map.of());

        assertThat(first.status()).isEqualTo(CapabilityResult.Status.FAILED);
        assertThat(String.valueOf(first.content().get("reason"))).contains("Unknown capability");
        assertThat(again.status()).isEqualTo(CapabilityResult.Status.FAILED);
        assertThat(again.content()).isEqualTo(first.content());
    }
}

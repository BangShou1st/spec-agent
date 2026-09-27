package com.specagent.capability;

import com.specagent.common.Ids;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
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
 * 文件名:CapabilityConcurrencyIntegrationTest.java
 *
 * 测试目标:针对真实 PostgreSQL 验证能力调用日志的并发与恢复语义——
 * invocation_key 上的唯一索引是执行归属的最终仲裁者,同一 key 的并发调用
 * 至多执行一次适配器且不泄漏约束冲突;已记录的 RUNNING/SUCCEEDED/FAILED
 * 状态在重试时不会互相混淆。
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
                // 竞争失败方必须读到胜出方已记录的结果——
                // 这里泄漏唯一约束错误即视为契约被破坏。
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
        // 原样返回已记录的失败;适配器不会被再次调用,
        // 也不会产生第二次外部尝试。
        assertThat(retry.status()).isEqualTo(CapabilityResult.Status.FAILED);
        assertThat(retry.content()).isEqualTo(first.content());
        assertThat(CountingCapabilityConfig.EXECUTIONS.get()).isEqualTo(1);
        assertThat(invocationRepository.findByInvocationKey(key).orElseThrow().status())
                .isEqualTo(CapabilityResult.Status.FAILED);
    }

    @Test
    void runningInvocationIsNeitherReplayedAsSuccessNorReExecuted() {
        String key = KEY_PREFIX + Ids.random();
        // 模拟并发持有者/崩溃窗口中的记录:已被认领但从未完成。
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

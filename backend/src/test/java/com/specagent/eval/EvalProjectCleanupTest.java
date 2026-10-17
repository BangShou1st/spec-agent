package com.specagent.eval;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvalProjectCleanupTest {

    @Test
    void cleanupAttemptsEveryProjectAndReportsPartialFailures() {
        EvalProjectCleanup cleanup = new EvalProjectCleanup();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        cleanup.register(first);
        cleanup.register(second);
        cleanup.register(third);
        List<UUID> attempted = new ArrayList<>();

        assertThatThrownBy(() -> cleanup.cleanupAll(projectId -> {
            attempted.add(projectId);
            if (projectId.equals(first) || projectId.equals(second)) {
                throw new IllegalStateException("cleanup failure " + projectId);
            }
        }, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("evaluation projects")
                .satisfies(failure -> assertThat(failure.getSuppressed()).hasSize(1));

        assertThat(attempted).containsExactly(first, second, third);
    }

    @Test
    void cleanupFailuresAreSuppressedOnTheOriginalTestFailure() throws Throwable {
        EvalProjectCleanup cleanup = new EvalProjectCleanup();
        cleanup.register(UUID.randomUUID());
        IllegalArgumentException testFailure = new IllegalArgumentException("original test failure");
        EvalCleanupFailureHandler handler = new EvalCleanupFailureHandler(primary ->
                cleanup.cleanupAll(projectId -> {
                    throw new IllegalStateException("cleanup failure");
                }, primary));

        assertThatThrownBy(() -> handler.handleTestExecutionException(null, testFailure))
                .isSameAs(testFailure);
        assertThat(testFailure.getSuppressed()).hasSize(1)
                .allSatisfy(suppressed -> assertThat(suppressed.getMessage())
                        .contains("evaluation projects"));
    }

    @Test
    void repeatedRunsOnlyCleanProjectsRegisteredForThatRun() {
        EvalProjectCleanup cleanup = new EvalProjectCleanup();
        UUID firstRunProject = UUID.randomUUID();
        UUID secondRunProject = UUID.randomUUID();
        List<UUID> attempted = new ArrayList<>();

        cleanup.register(firstRunProject);
        cleanup.cleanupAll(attempted::add, null);
        cleanup.register(secondRunProject);
        cleanup.cleanupAll(attempted::add, null);

        assertThat(attempted).containsExactly(firstRunProject, secondRunProject);
    }
}

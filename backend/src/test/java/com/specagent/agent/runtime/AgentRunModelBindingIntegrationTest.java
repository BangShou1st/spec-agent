package com.specagent.agent.runtime;

import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AgentRunModelBindingIntegrationTest {

    @Autowired private AgentRunModelBindingRepository bindings;
    @Autowired private AgentRunService runs;
    @Autowired private ProjectService projects;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void concurrentFirstBindingsHaveOneDatabaseWinnerAndSurviveTerminalization() throws Exception {
        Project project = projects.createProject("Model binding " + java.util.UUID.randomUUID());
        AgentRun run = runs.create(project.id(), project.activeRouteId(),
                AgentRunTriggerType.INITIAL_REQUIREMENT, null, null);
        String fingerprintA = "a".repeat(64);
        String fingerprintB = "b".repeat(64);
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            results.add(pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return bindings.bindOrVerify(run.id(), fingerprintA);
            }));
            results.add(pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return bindings.bindOrVerify(run.id(), fingerprintB);
            }));

            boolean aWon = results.get(0).get(20, TimeUnit.SECONDS);
            boolean bWon = results.get(1).get(20, TimeUnit.SECONDS);
            assertThat(aWon).isNotEqualTo(bWon);
            String winner = aWon ? fingerprintA : fingerprintB;
            String loser = aWon ? fingerprintB : fingerprintA;
            assertThat(bindings.bindOrVerify(run.id(), winner)).isTrue();
            assertThat(bindings.bindOrVerify(run.id(), loser)).isFalse();

            runs.complete(run.id(), AgentRunStatus.COMPLETED, "completed");
            assertThat(bindings.bindOrVerify(run.id(), winner)).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void legacyActiveRunWithoutARecoverableBindingFailsClosed() {
        Project project = projects.createProject("Legacy model binding " + java.util.UUID.randomUUID());
        AgentRun run = runs.create(project.id(), project.activeRouteId(),
                AgentRunTriggerType.INITIAL_REQUIREMENT, null, null);
        jdbc.update("UPDATE agent_runs SET status = 'model_called', "
                + "model_binding_fingerprint = 'legacy-unbound' WHERE id = ?", run.id());

        assertThat(bindings.bindOrVerify(run.id(), "a".repeat(64))).isFalse();
    }
}

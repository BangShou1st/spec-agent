package com.specagent.common.health;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1-2 production fail-safe: a deployment that serves the run API must never
 * report healthy while its worker is off. With the worker disabled the health
 * endpoint reports AGENT_WORKER_UNAVAILABLE (503); with it enabled the
 * process reports UP and the scheduling bean exists.
 */
class ProductionRuntimeWorkerHealthTest {

    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @TestPropertySource(properties = "spec.agent.brain.worker.enabled=true")
    static class WorkerEnabled {

        @Autowired
        private MockMvc mockMvc;

        @Autowired(required = false)
        private com.specagent.agent.runtime.RunWorkerSchedulingConfig schedulingConfig;

        @Test
        void productionRuntimeHasWorkingWorkerOrFailsFast() throws Exception {
            // The executor scheduling bean exists when the worker is on.
            assertThat(schedulingConfig).isNotNull();
            mockMvc.perform(get("/api/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.worker").value("ENABLED"));
        }
    }

    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @TestPropertySource(properties = "spec.agent.brain.worker.enabled=false")
    static class WorkerDisabled {

        @Autowired
        private MockMvc mockMvc;

        @Autowired(required = false)
        private com.specagent.agent.runtime.RunWorkerSchedulingConfig schedulingConfig;

        @Test
        void apiWithoutWorkerIsNeverReportedHealthy() throws Exception {
            assertThat(schedulingConfig).isNull();
            mockMvc.perform(get("/api/health"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("DEGRADED"))
                    .andExpect(jsonPath("$.reason").value("AGENT_WORKER_UNAVAILABLE"));
        }
    }
}

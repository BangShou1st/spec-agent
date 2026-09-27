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
 * 文件名:ProductionRuntimeWorkerHealthTest.java
 *
 * 测试目标:验证 P1-2 生产级保底规则——对外提供 run API 的部署在 worker
 * 关闭时绝不能报告健康。worker 关闭时健康端点返回 AGENT_WORKER_UNAVAILABLE
 * (503);worker 开启时进程报告 UP 且调度 bean 存在。
 */
class ProductionRuntimeWorkerHealthTest {

    // 与 DraftQuestionAsyncPollerIntegrationTest 相同的隔离规则:这是另一个
    // 会针对共享测试数据库启动真实 @Scheduled RunWorkerPoller 的上下文,
    // 因此类结束后上下文不能继续缓存(否则轮询会一直跑)。
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @TestPropertySource(properties = "spec.agent.brain.worker.enabled=true")
    @org.springframework.test.annotation.DirtiesContext(
            classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
    static class WorkerEnabled {

        @Autowired
        private MockMvc mockMvc;

        @Autowired(required = false)
        private com.specagent.agent.runtime.RunWorkerSchedulingConfig schedulingConfig;

        @Test
        void productionRuntimeHasWorkingWorkerOrFailsFast() throws Exception {
            // worker 开启时调度 bean 存在。
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

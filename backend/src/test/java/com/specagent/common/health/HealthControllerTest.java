package com.specagent.common.health;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 文件名:HealthControllerTest.java
 *
 * 测试目标:验证健康检查端点 /api/health 返回 UP 状态、服务名与时间戳,
 * 确保基础健康检查可用。
 */
@WebMvcTest(HealthController.class)
@ActiveProfiles("test")
@org.springframework.test.context.TestPropertySource(
        properties = "spec.agent.brain.worker.enabled=true")
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /** 测试桩:轮询门控状态可切换,模拟启动中间态/恢复失败/正常服务。 */
    static volatile boolean pollingOpen = true;
    static volatile String recoveryError = null;

    @org.springframework.boot.test.context.TestConfiguration
    static class StubGateConfig {
        @org.springframework.context.annotation.Bean
        WorkerRuntimeStatus stubWorkerRuntimeStatus() {
            return new WorkerRuntimeStatus() {
                @Override public boolean isPollingOpen() { return pollingOpen; }
                @Override public boolean isStartupRecoveryEnabled() { return true; }
                @Override public String getRecoveryError() { return recoveryError; }
            };
        }
    }

    @Test
    void healthEndpointReturnsUp() throws Exception {
        // 轮询开放:UP + 基本信息
        pollingOpen = true;
        recoveryError = null;
        mockMvc.perform(get("/api/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.service").value("spec-agent"))
            .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void healthReportsPendingWhilePollingIsClosed() throws Exception {
        // 启动中间态:worker 已启用但轮询门控未开放 → 503,绝不假装健康
        pollingOpen = false;
        recoveryError = null;
        try {
            mockMvc.perform(get("/api/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.reason").value("WORKER_POLLING_PENDING"));
        } finally {
            pollingOpen = true;
        }
    }

    @Test
    void healthSurfacesRecoveryFailureWithoutDying() throws Exception {
        // 恢复失败但队列存活:UP + ORPHAN_RECOVERY_FAILED 告警字段
        pollingOpen = true;
        recoveryError = "IllegalStateException";
        try {
            mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.orphanRecovery").value("FAILED"))
                .andExpect(jsonPath("$.orphanRecoveryError").value("IllegalStateException"));
        } finally {
            recoveryError = null;
        }
    }
}

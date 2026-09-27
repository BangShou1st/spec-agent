package com.specagent.common.health;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 文件名:HealthController.java
 *
 * 用途:健康检查端点({@code GET /api/health}),返回服务当前状态,
 * 供部署与运维探活使用。
 *
 * 除了常规的 UP 状态,还感知本进程的执行器生命周期:一个只服务 run API
 * 但没有 worker 的进程不能报完全健康(AGENT_WORKER_UNAVAILABLE);worker
 * 已启用但轮询门控尚未开放(应用就绪前/孤儿恢复进行中)时报
 * WORKER_POLLING_PENDING——这是短暂的启动中间态;若孤儿恢复失败,轮询
 * 仍会开放(队列活性优先),但健康检查会携带 ORPHAN_RECOVERY_FAILED,
 * 绝不让"恢复失败"看起来像一切正常。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final boolean workerEnabled;
    private final ObjectProvider<WorkerRuntimeStatus> gate;

    public HealthController(
            @Value("${spec.agent.brain.worker.enabled:true}") boolean workerEnabled,
            ObjectProvider<WorkerRuntimeStatus> gate) {
        this.workerEnabled = workerEnabled;
        this.gate = gate;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        // 故障兜底:只服务 run API 但没有 worker 的进程绝不报完全健康,
        // 否则 run 会被接收却永远无人认领(AGENT_WORKER_UNAVAILABLE)。
        if (!workerEnabled) {
            return ResponseEntity.status(503).body(Map.of(
                "status", "DEGRADED",
                "service", "spec-agent",
                "reason", "AGENT_WORKER_UNAVAILABLE",
                "timestamp", Instant.now().toString()
            ));
        }
        WorkerRuntimeStatus polling = gate.getIfAvailable();
        if (polling == null || !polling.isPollingOpen()) {
            return ResponseEntity.status(503).body(Map.of(
                "status", "DEGRADED",
                "service", "spec-agent",
                "reason", "WORKER_POLLING_PENDING",
                "timestamp", Instant.now().toString()
            ));
        }
        // 租约丢失是终态故障:数据库所有权已不归本进程,新任务不可能被
        // 认领(owned() 为 false),在途写入也被拒绝。绝不报 UP。
        if (polling.isExecutorLeaseLost()) {
            return ResponseEntity.status(503).body(Map.of(
                "status", "DEGRADED",
                "service", "spec-agent",
                "reason", "EXECUTOR_LEASE_LOST",
                "leaseLostReason", String.valueOf(polling.getExecutorLeaseLostReason()),
                "timestamp", Instant.now().toString()
            ));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("status", "UP");
        body.put("service", "spec-agent");
        body.put("worker", "ENABLED");
        body.put("startupRecovery", polling.isStartupRecoveryEnabled()
                ? "ENABLED" : "DISABLED");
        if (polling.getRecoveryError() != null) {
            body.put("orphanRecovery", "FAILED");
            body.put("orphanRecoveryError", polling.getRecoveryError());
        }
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(body);
    }
}

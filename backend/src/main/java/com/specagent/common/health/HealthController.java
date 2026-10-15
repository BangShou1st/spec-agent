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
    private String securityMode="LOCAL";
    @org.springframework.beans.factory.annotation.Autowired
    public void configureSecurityMode(@Value("${spec.agent.security.mode:LOCAL}") String securityMode) {
        this.securityMode=securityMode;
    }

    private final boolean workerEnabled;
    private final ObjectProvider<WorkerRuntimeStatus> gate;
    private final org.springframework.beans.factory.ObjectProvider<ExecutionModeAdmissionStatus>
            admission;

    public HealthController(
            @Value("${spec.agent.brain.worker.enabled:true}") boolean workerEnabled,
            ObjectProvider<WorkerRuntimeStatus> gate,
            org.springframework.beans.factory.ObjectProvider<ExecutionModeAdmissionStatus> admission) {
        this.workerEnabled = workerEnabled;
        this.gate = gate;
        this.admission = admission;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        ResponseEntity<Map<String,Object>> response=detailedHealth();
        if("OIDC".equalsIgnoreCase(securityMode)) return ResponseEntity.status(response.getStatusCode())
                .body(Map.of("status",response.getBody().get("status")));
        return response;
    }
    private ResponseEntity<Map<String,Object>> detailedHealth() {
        // 模式准入丢失对 API-only 实例同样致命:入队路径必须与 Worker 的调度
        // 模式一致(design §11),因此该检查先于 worker-enabled 分支。
        ExecutionModeAdmissionStatus mode = admission.getIfAvailable();
        if (mode != null && mode.isLost()) {
            return ResponseEntity.status(503).body(Map.of(
                "status", "DEGRADED",
                "service", "spec-agent",
                "reason", "EXECUTION_MODE_ADMISSION_LOST",
                "admissionLostReason", String.valueOf(mode.getLostReason()),
                "timestamp", Instant.now().toString()
            ));
        }
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
        // 多 Worker(任务级所有权)是独立的运行形态:没有全库租约,"租约丢失"
        // 不是它的失败模式。明确暴露形态,避免运维把单实例语义套到多实例上。
        body.put("executionOwnership", polling.isMultiWorkerTaskOwnership()
                ? "PER_TASK" : "SINGLE_EXECUTOR_LEASE");
        // 数据库范围的调度模式准入(design §11):运维可以确认本实例与舰队
        // 的模式一致,以及准入协议是否处于激活状态。
        body.put("executionModeAdmission", mode == null
                ? "DISABLED" : String.valueOf(mode.admittedMode()));
        // worker 身份(design §9):多 Worker 部署用它把 runtime_tasks.worker_id
        // 映射到具体实例;无执行身份的进程为 null。
        if (polling.getWorkerId() != null) {
            body.put("workerId", polling.getWorkerId());
        }
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

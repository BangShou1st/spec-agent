package com.specagent.agent.runtime;

import java.time.Instant;
import java.util.UUID;

/**
 * 文件名:AgentRun.java
 *
 * 用途:表示一次受控的 Agent 执行(不可变领域对象)。AgentRun 记录触发它的
 * operation 以及运行期间产出的各类运行时记录(节点、回答、补丁、规格快照)。
 * 它本身不持有持久化的需求状态;runtime 内核(不可变事实)始终是唯一事实来源。
 * 在"命令 → 持久化 → Brain → 校验 → checkpoint"链路中,它由各 Cycle 服务创建,
 * 是追踪执行状态与父子链路的核心记录。
 */
public class AgentRun {

    private final UUID id;
    private final UUID projectId;
    private final UUID routeId;
    private final AgentRunTriggerType triggerType;
    private final UUID inputNodeId;
    private final UUID contextSnapshotId;
    private final UUID producedNodeId;
    private final UUID producedAnswerId;
    private final UUID producedPatchId;
    private final UUID producedSpecSnapshotId;
    private final AgentRunStatus status;
    private final String trace;
    private final String operation;
    private final String idempotencyKey;
    private final String requestFingerprint;
    private final Instant createdAt;
    private final Instant completedAt;
    private final UUID parentRunId;
    private final UUID rootRunId;
    private final Integer cycleIndex;

    public AgentRun(UUID id,
                    UUID projectId,
                    UUID routeId,
                    AgentRunTriggerType triggerType,
                    UUID inputNodeId,
                    UUID contextSnapshotId,
                    UUID producedNodeId,
                    UUID producedAnswerId,
                    UUID producedPatchId,
                    UUID producedSpecSnapshotId,
                    AgentRunStatus status,
                    String trace,
                    String operation,
                    String idempotencyKey,
                    String requestFingerprint,
                    Instant createdAt,
                    Instant completedAt) {
        this(id, projectId, routeId, triggerType, inputNodeId, contextSnapshotId,
                producedNodeId, producedAnswerId, producedPatchId, producedSpecSnapshotId,
                status, trace, operation, idempotencyKey, requestFingerprint,
                createdAt, completedAt, null, null, null);
    }

    /**
     * 完整构造器,包含自治续跑链路(parentRunId/rootRunId/cycleIndex)。
     *
     * {@code parentRunId} 指向其终止边界触发本 run 的父 run;{@code rootRunId}
     * 指向链头,便于整链查询;{@code cycleIndex} 记录子链深度,链根为 0。
     * 对"续跑机制引入之前"的历史 run 以及外部触发的 run,三者均为 null
     * (惰性链路标识:null 视为"自身即链根、位于第 0 轮")。
     *
     * 注意:这套链路字段与 {@code AgentRunService.create} 重载携带的遗留
     * {@code createdByRunId} 创建提示无关——后者不落库到 run 行。两个概念
     * 绝不能混用。
     */
    public AgentRun(UUID id,
                    UUID projectId,
                    UUID routeId,
                    AgentRunTriggerType triggerType,
                    UUID inputNodeId,
                    UUID contextSnapshotId,
                    UUID producedNodeId,
                    UUID producedAnswerId,
                    UUID producedPatchId,
                    UUID producedSpecSnapshotId,
                    AgentRunStatus status,
                    String trace,
                    String operation,
                    String idempotencyKey,
                    String requestFingerprint,
                    Instant createdAt,
                    Instant completedAt,
                    UUID parentRunId,
                    UUID rootRunId,
                    Integer cycleIndex) {
        this.id = id;
        this.projectId = projectId;
        this.routeId = routeId;
        this.triggerType = triggerType;
        this.inputNodeId = inputNodeId;
        this.contextSnapshotId = contextSnapshotId;
        this.producedNodeId = producedNodeId;
        this.producedAnswerId = producedAnswerId;
        this.producedPatchId = producedPatchId;
        this.producedSpecSnapshotId = producedSpecSnapshotId;
        this.status = status;
        this.trace = trace;
        this.operation = operation;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
        this.parentRunId = parentRunId;
        this.rootRunId = rootRunId;
        this.cycleIndex = cycleIndex;
    }

    public UUID id() {
        return id;
    }

    public UUID projectId() {
        return projectId;
    }

    public UUID routeId() {
        return routeId;
    }

    public AgentRunTriggerType triggerType() {
        return triggerType;
    }

    public UUID inputNodeId() {
        return inputNodeId;
    }

    public UUID contextSnapshotId() {
        return contextSnapshotId;
    }

    public UUID producedNodeId() {
        return producedNodeId;
    }

    public UUID producedAnswerId() {
        return producedAnswerId;
    }

    public UUID producedPatchId() {
        return producedPatchId;
    }

    public UUID producedSpecSnapshotId() {
        return producedSpecSnapshotId;
    }

    public AgentRunStatus status() {
        return status;
    }

    public String trace() {
        return trace;
    }

    public String operation() {
        return operation;
    }

    /**
     * 客户端提供的稳定幂等键,用于 create-run 重试去重(内部生成的 run 为 null)。
     * 同一项目内携带相同 key 的两次创建请求,只会落库为一个 run。
     */
    public String idempotencyKey() {
        return idempotencyKey;
    }

    /**
     * 客户端幂等 run 的规范化逻辑请求内容的 SHA-256 指纹。
     * 历史数据允许没有指纹(为 null)。
     */
    public String requestFingerprint() {
        return requestFingerprint;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    /**
     * 其终止边界触发本 run 的父 run;链根与续跑机制之前的行该值为 null。
     */
    public UUID parentRunId() {
        return parentRunId;
    }

    /**
     * 本自治续跑链的链头 run,便于整链查询;链路标识未落库时为 null(视为本 run 自身)。
     */
    public UUID rootRunId() {
        return rootRunId;
    }

    /**
     * 链内的子代深度,链根为 0;续跑机制之前的行该值为 null(视为 0)。
     */
    public Integer cycleIndex() {
        return cycleIndex;
    }
}

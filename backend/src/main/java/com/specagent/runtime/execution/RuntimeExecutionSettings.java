package com.specagent.runtime.execution;

/**
 * 文件名:RuntimeExecutionSettings.java
 *
 * 用途:公共调度层的可配置初值(docs/v2/
 * PHASE1_MULTI_WORKER_EXECUTION_DESIGN.md §9)。全部为实验初值,只有测量
 * 之后才能成为承载承诺,因此一律可配置。
 *
 * <p>绑定前缀 {@code spec.agent.execution.*}。
 */
@org.springframework.boot.context.properties.ConfigurationProperties(prefix = "spec.agent.execution")
public class RuntimeExecutionSettings {

    /** 本进程实际空闲执行槽(本地并发上限,不是全局限额)。 */
    private int localSlots = 2;

    /** 租约时长(秒)。 */
    private int leaseSeconds = 60;

    /** 心跳续租间隔(秒);必须显著小于 leaseSeconds。 */
    private int heartbeatSeconds = 10;

    /** 认领基准轮询间隔(毫秒),调用方加随机抖动。 */
    private long claimIntervalMs = 500;

    /** 过期租约扫描间隔(毫秒)。 */
    private long expiryScanIntervalMs = 5000;

    /** 每次扫描的候选上限(有界扫描,避免一个繁忙范围挡住其他任务)。 */
    private int claimScanLimit = 8;

    /** 每个任务的最大尝试次数。 */
    private int maxAttempts = 3;

    /** 画布任务的绝对总截止时间(秒);首次实际执行时固定,恢复不重置。 */
    private int workspaceTimeoutSeconds = 900;

    /** 全局助手任务的绝对总截止时间(秒);保持既有 180 秒预算。 */
    private int globalAssistantTimeoutSeconds = 180;

    /** 本地执行器提交失败后的重排延迟(秒)。 */
    private int requeueDelaySeconds = 2;

    /** 恢复判定安全后的重排延迟(秒)。 */
    private int recoveryRequeueDelaySeconds = 2;

    /** 单次过期扫描处理的批大小。 */
    private int expiryScanBatch = 16;

    /** 总排队上限;达到上限时在创建 Run 前拒绝并返回可重试错误。 */
    private int maxQueuedTasks = 1000;

    /** 单范围排队上限。 */
    private int maxQueuedPerScope = 20;

    /** 关闭时允许 drain 的最长时间(秒)。 */
    private int shutdownDrainSeconds = 30;

    public int getLocalSlots() {
        return localSlots;
    }

    public void setLocalSlots(int localSlots) {
        this.localSlots = Math.max(1, localSlots);
    }

    public int getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(int leaseSeconds) {
        this.leaseSeconds = Math.max(5, leaseSeconds);
    }

    public int getHeartbeatSeconds() {
        return heartbeatSeconds;
    }

    public void setHeartbeatSeconds(int heartbeatSeconds) {
        this.heartbeatSeconds = Math.max(1, heartbeatSeconds);
    }

    public long getClaimIntervalMs() {
        return claimIntervalMs;
    }

    public void setClaimIntervalMs(long claimIntervalMs) {
        this.claimIntervalMs = Math.max(50, claimIntervalMs);
    }

    public long getExpiryScanIntervalMs() {
        return expiryScanIntervalMs;
    }

    public void setExpiryScanIntervalMs(long expiryScanIntervalMs) {
        this.expiryScanIntervalMs = Math.max(500, expiryScanIntervalMs);
    }

    public int getClaimScanLimit() {
        return claimScanLimit;
    }

    public void setClaimScanLimit(int claimScanLimit) {
        this.claimScanLimit = Math.max(1, claimScanLimit);
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public int getWorkspaceTimeoutSeconds() {
        return workspaceTimeoutSeconds;
    }

    public void setWorkspaceTimeoutSeconds(int workspaceTimeoutSeconds) {
        this.workspaceTimeoutSeconds = Math.max(1, workspaceTimeoutSeconds);
    }

    public int getGlobalAssistantTimeoutSeconds() {
        return globalAssistantTimeoutSeconds;
    }

    public void setGlobalAssistantTimeoutSeconds(int globalAssistantTimeoutSeconds) {
        this.globalAssistantTimeoutSeconds = Math.max(1, globalAssistantTimeoutSeconds);
    }

    public int getRequeueDelaySeconds() {
        return requeueDelaySeconds;
    }

    public void setRequeueDelaySeconds(int requeueDelaySeconds) {
        this.requeueDelaySeconds = Math.max(0, requeueDelaySeconds);
    }

    public int getRecoveryRequeueDelaySeconds() {
        return recoveryRequeueDelaySeconds;
    }

    public void setRecoveryRequeueDelaySeconds(int recoveryRequeueDelaySeconds) {
        this.recoveryRequeueDelaySeconds = Math.max(0, recoveryRequeueDelaySeconds);
    }

    public int getExpiryScanBatch() {
        return expiryScanBatch;
    }

    public void setExpiryScanBatch(int expiryScanBatch) {
        this.expiryScanBatch = Math.max(1, expiryScanBatch);
    }

    public int getMaxQueuedTasks() {
        return maxQueuedTasks;
    }

    public void setMaxQueuedTasks(int maxQueuedTasks) {
        this.maxQueuedTasks = Math.max(1, maxQueuedTasks);
    }

    public int getMaxQueuedPerScope() {
        return maxQueuedPerScope;
    }

    public void setMaxQueuedPerScope(int maxQueuedPerScope) {
        this.maxQueuedPerScope = Math.max(1, maxQueuedPerScope);
    }

    public int getShutdownDrainSeconds() {
        return shutdownDrainSeconds;
    }

    public void setShutdownDrainSeconds(int shutdownDrainSeconds) {
        this.shutdownDrainSeconds = Math.max(0, shutdownDrainSeconds);
    }

    /** 某任务族的绝对总截止时间(秒)。 */
    public int timeoutSecondsFor(RuntimeTaskFamily family) {
        return family == RuntimeTaskFamily.WORKSPACE
                ? workspaceTimeoutSeconds : globalAssistantTimeoutSeconds;
    }
}

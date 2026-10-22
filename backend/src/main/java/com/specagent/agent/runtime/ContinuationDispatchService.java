package com.specagent.agent.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 文件名:ContinuationDispatchService.java
 *
 * 用途:持久化的续跑分发器:既是低延迟快速路径,也是 {@code agent_run_continuation_checks}
 * 的崩溃恢复扫描器。
 *
 * 依赖只有检查 outbox 与协调器——没有模型、路线、上下文、动作、审批或任何
 * 语义规划输入。每个决策都通过 {@link ContinuationCoordinator#continueIfEligible(UUID)}
 * 重新读取持久化的 run 事实,因此崩溃后的重放会得到与丢失的 afterCommit 相同的答案。
 *
 * 事务形态(最小化、不依赖框架自代理):每次评估都在一个显式
 * {@link TransactionTemplate} 事务里同时完成"子 run 创建 + 精确代数标记"——
 * 绝不是自调用的 {@code @Transactional} 代理方法,因此无论本 bean 如何被调用,
 * 原子性都成立。标记阶段失败会回滚整个评估:待处理检查保持待处理,由恢复
 * 流程安全重放。子 run 创建之后、标记之前崩溃,会留下"待处理行 + 已存在的
 * 子 run";恢复时观察到 {@code ALREADY_CONTINUED},只标记已处理而不创建
 * 第二个子 run(V23 单子索引加确定性 {@code continue:<parentRunId>} key 裁决)。
 *
 * 代数门:完成只标记自己实际评估的那个代数。并发的再次请求(审批接受
 * 重新打开停靠的检查)会先把代数加一,因此过期的完成只标记 0 行,
 * 新代数保持待处理,直到恢复流程收敛它。
 */
@Service
public class ContinuationDispatchService {

    private static final Logger LOG = LoggerFactory.getLogger(ContinuationDispatchService.class);

    private final ContinuationCheckRepository checkRepository;
    private final ContinuationCoordinator coordinator;
    private final TransactionTemplate transactionTemplate;
    /**
     * 本进程的执行身份(design §10)。非 null 时后台恢复扫描走"认领"路径:
     * 由数据库裁决每条待处理检查归属哪个 Worker,避免多实例重复评估。
     * 单执行器模式下为 null,行为与历史一致。
     */
    private final UUID workerId;
    private com.specagent.capability.CapabilityInvocationRepository deferredInvocations;
    private WaitDependencyRepository waitSubscriptions;
    private java.util.List<com.specagent.common.DeferredCompletionRecoveryPort> completionSources=java.util.List.of();

    @org.springframework.beans.factory.annotation.Autowired
    public void setCompletionSources(java.util.List<com.specagent.common.DeferredCompletionRecoveryPort> sources) {
        this.completionSources=java.util.List.copyOf(sources);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setWaitSubscriptions(WaitDependencyRepository subscriptions) { this.waitSubscriptions = subscriptions; }

    @org.springframework.beans.factory.annotation.Autowired
    public void setDeferredInvocations(com.specagent.capability.CapabilityInvocationRepository repository) {
        this.deferredInvocations = repository;
    }

    public ContinuationDispatchService(ContinuationCheckRepository checkRepository,
                                       ContinuationCoordinator coordinator,
                                       TransactionTemplate transactionTemplate) {
        this(checkRepository, coordinator, transactionTemplate, (UUID) null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ContinuationDispatchService(ContinuationCheckRepository checkRepository,
                                       ContinuationCoordinator coordinator,
                                       TransactionTemplate transactionTemplate,
                                       org.springframework.beans.factory.ObjectProvider<
                                               com.specagent.runtime.execution.WorkerIdentity> workerIdentity) {
        this(checkRepository, coordinator, transactionTemplate,
                workerIdentity == null || workerIdentity.getIfAvailable() == null
                        ? null : workerIdentity.getIfAvailable().id());
    }

    private ContinuationDispatchService(ContinuationCheckRepository checkRepository,
                                        ContinuationCoordinator coordinator,
                                        TransactionTemplate transactionTemplate,
                                        UUID workerId) {
        this.checkRepository = checkRepository;
        this.coordinator = coordinator;
        this.transactionTemplate = transactionTemplate;
        this.workerId = workerId;
    }

    /** 为一个终态 run 请求评估(加入终态事务)。 */
    public void request(UUID runId) {
        checkRepository.request(runId);
    }

    /**
     * 评估单个 run 的待处理代数:满足条件时创建子 run,然后把该代数精确标记
     * 为已处理——两者在同一个显式事务内。瞬时失败不标记、直接向上抛出,由
     * 恢复扫描器重试;已创建的子 run 会经由 {@code ALREADY_CONTINUED} 收敛为
     * "只标记、不建第二行"。已处理检查的重复投递是空操作(终态 run 本身
     * 绝不会被重新执行——见 {@code RunWorker} 的 fail-closed 设计)。
     */
    public void process(UUID runId) {
        ContinuationCheck check = checkRepository.findPendingByRunId(runId)
                .orElse(null);
        if (check == null) {
            return;
        }
        process(check);
    }

    /**
     * 评估一条待处理的检查代数。包内可见,专供代数竞态测试使用:它固定了
     * 公共路径只能靠时序才能触发的确切 ABA 交错(代数 1 在途时请求代数 2)。
     */
    void process(ContinuationCheck check) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                coordinator.continueIfEligible(check.runId());
                checkRepository.markProcessed(check.runId(), check.generation());
            });
        } catch (com.specagent.access.AccessDeniedException denied) {
            // The evaluation transaction has already rolled back. Do not persist a
            // cancellation inside its rollback-only scope, or endlessly retry a revoked WAIT.
            var permanent = java.util.Set.of(com.specagent.access.AccessReason.RESOURCE_NOT_VISIBLE,
                    com.specagent.access.AccessReason.USER_DISABLED,
                    com.specagent.access.AccessReason.ORG_DISABLED,
                    com.specagent.access.AccessReason.NOT_ORG_MEMBER,
                    com.specagent.access.AccessReason.MEMBERSHIP_DISABLED,
                    com.specagent.access.AccessReason.PROJECT_CAPABILITY_MISSING,
                    com.specagent.access.AccessReason.ORG_CAPABILITY_MISSING);
            if (waitSubscriptions == null || !permanent.contains(denied.reasonCode())) throw denied;
            var cancellation = new TransactionTemplate(java.util.Objects.requireNonNull(
                    transactionTemplate.getTransactionManager(), "WAIT cancellation requires a transaction manager"));
            cancellation.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            cancellation.executeWithoutResult(status -> {
                if (waitSubscriptions.invalidateDenied(check.runId()) != 1) throw denied;
                checkRepository.markProcessed(check.runId(), check.generation());
            });
        }
    }

    /**
     * 按最老优先重放待处理检查;单行失败绝不阻塞其他行。
     *
     * <p><b>worker 归属(design §10)。</b>任务级调度下,每条检查先由数据库裁决
     * 归属(带会过期的认领),因此多实例不会重复评估同一条。瞬时失败的检查会
     * <b>立即释放认领</b>,而不是等到认领过期——否则一次可重试的失败会白白
     * 延迟一个认领周期。
     */
    public void recoverPending() {
        recoverPending(100);
    }

    /** 认领有效期(秒):显著大于单次评估耗时,使崩溃后的接管足够及时。 */
    private static final int CLAIM_SECONDS = 60;

    public void recoverPending(int limit) {
        for(var source:completionSources) {
            try { source.recover(limit); }
            catch(RuntimeException ex) {
                LOG.warn("Deferred business outcome scan failed; retrying next poll ({})",ex.getClass().getSimpleName());
            }
        }
        if (deferredInvocations != null) {
            try {
                deferredInvocations.expirePending(limit);
            } catch (RuntimeException ex) {
                // The timeout transaction rolls back independently. Retry it next tick
                // without starving checks already committed by unrelated runs.
                LOG.warn("Deferred capability timeout scan failed; retrying next poll ({})",
                        ex.getClass().getSimpleName());
            }
        }
        List<ContinuationCheck> pending =
                checkRepository.claimPending(limit, workerId, CLAIM_SECONDS);
        for (ContinuationCheck check : pending) {
            try {
                process(check);
            } catch (RuntimeException ex) {
                LOG.warn("Continuation recovery deferred for run {}: {}",
                        check.runId(), ex.getMessage());
                releaseClaimQuietly(check.runId());
            }
        }
    }

    /** 释放认领以便下轮立即重试;释放失败只记为告警,由 claim 过期兜底。 */
    private void releaseClaimQuietly(UUID runId) {
        if (workerId == null) {
            return;
        }
        try {
            checkRepository.releaseClaim(runId, workerId);
        } catch (RuntimeException ex) {
            LOG.warn("Failed to release continuation claim for run {}: {}",
                    runId, ex.getMessage());
        }
    }
}

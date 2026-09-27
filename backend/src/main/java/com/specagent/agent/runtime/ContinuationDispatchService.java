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

    public ContinuationDispatchService(ContinuationCheckRepository checkRepository,
                                       ContinuationCoordinator coordinator,
                                       TransactionTemplate transactionTemplate) {
        this.checkRepository = checkRepository;
        this.coordinator = coordinator;
        this.transactionTemplate = transactionTemplate;
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
        transactionTemplate.executeWithoutResult(status -> {
            coordinator.continueIfEligible(check.runId());
            checkRepository.markProcessed(check.runId(), check.generation());
        });
    }

    /** 按最老优先重放待处理检查;单行失败绝不阻塞其他行。 */
    public void recoverPending() {
        recoverPending(100);
    }

    public void recoverPending(int limit) {
        List<ContinuationCheck> pending = checkRepository.findPending(limit);
        for (ContinuationCheck check : pending) {
            try {
                process(check);
            } catch (RuntimeException ex) {
                LOG.warn("Continuation recovery deferred for run {}: {}",
                        check.runId(), ex.getMessage());
            }
        }
    }
}

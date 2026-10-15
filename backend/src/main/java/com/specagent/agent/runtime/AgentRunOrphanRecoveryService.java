package com.specagent.agent.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 文件名:AgentRunOrphanRecoveryService.java
 *
 * 用途:工作区 AgentRun 的启动期孤儿恢复。单实例部署边界:本产品的
 * 正式运行形态是一个后端进程 + 一个内置 worker;进程启动时数据库里
 * 任何非终态(created 除外——它只是排队,尚未被认领,等待本进程执行)
 * 之外的 running/context_built/model_called/reflected/persisted 行都
 * 属于已死的旧执行器,没有任何存活进程会再推进它们。
 *
 * 收敛策略是诚实终态化而非重放:
 * - 崩溃时刻的模型调用、反思与持久化进度不可靠,重放既可能重复写入
 *   不可变 Answer,也可能自动重放外部副作用——都被核心约束禁止。
 * - 每个 run 事务内独立地置为 FAILED(trace=failed:interrupted_by_restart)
 *   并追加 RUN_FAILED 事件,携带用户可读的中断说明;前端据此显示失败
 *   并给出重新发起的入口。
 * - 终态 run 与已产生的 Answer/补丁/规格一律不碰。
 * - 不派发任何自治续跑;续跑只属于正常终态路径。
 *
 * 多实例注意:本恢复不实现所有权租约,因为它依赖"同一时刻至多一个
 * 执行器进程"这一产品边界。若未来允许多实例,必须先引入租约/所有权,
 * 否则启动恢复会误终止其他存活执行器的任务。
 */
@Service
public class AgentRunOrphanRecoveryService {

    private static final Logger LOG = LoggerFactory.getLogger(AgentRunOrphanRecoveryService.class);

    /** 启动恢复写入的稳定 trace 前缀,与 {@link RunWorker} 的失败写法一致。 */
    public static final String INTERRUPTED_TRACE = "failed:" + RunFailureReasons.INTERRUPTED_BY_RESTART;

    private final AgentRunRepository agentRunRepository;
    private final AgentRunFailureService failureService;
    private final TransactionTemplate txTemplate;
    /**
     * 任务级所有权模式(多 Worker)下用于识别"属于其他存活 Worker 的活跃 Run"。
     * 单执行器模式下为 null,行为与历史完全一致。
     */
    private final com.specagent.runtime.execution.RuntimeTaskRepository runtimeTasks;

    public AgentRunOrphanRecoveryService(AgentRunRepository agentRunRepository,
                                         AgentRunFailureService failureService,
                                         TransactionTemplate txTemplate) {
        this(agentRunRepository, failureService, txTemplate,
                (com.specagent.runtime.execution.RuntimeTaskRepository) null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentRunOrphanRecoveryService(AgentRunRepository agentRunRepository,
                                         AgentRunFailureService failureService,
                                         TransactionTemplate txTemplate,
                                         org.springframework.beans.factory.ObjectProvider<
                                                 com.specagent.runtime.execution.RuntimeTaskRepository>
                                                 runtimeTasks) {
        this(agentRunRepository, failureService, txTemplate,
                runtimeTasks == null ? null : runtimeTasks.getIfAvailable());
    }

    private AgentRunOrphanRecoveryService(AgentRunRepository agentRunRepository,
                                          AgentRunFailureService failureService,
                                          TransactionTemplate txTemplate,
                                          com.specagent.runtime.execution.RuntimeTaskRepository runtimeTasks) {
        this.agentRunRepository = agentRunRepository;
        this.failureService = failureService;
        this.txTemplate = txTemplate;
        this.runtimeTasks = runtimeTasks;
    }

    /**
     * 把旧执行器遗留的全部非终态 run 收敛为失败。逐个 run 在独立事务中
     * 处理,单个坏行不阻塞其余恢复;幂等——重复调用找不到可恢复行。
     *
     * <p>部分失败不再被吞掉:每个恢复失败都计入 {@link OrphanRecoveryResult
     * #failed()},调用方(监听器)必须把它上报到健康状态——"恢复了大部分"
     * 绝不能被误读为"恢复成功"。
     *
     * @return 恢复结果(成功与失败计数)
     */
    public OrphanRecoveryResult recoverOrphans() {
        List<AgentRun> nonTerminal = agentRunRepository.findNonTerminal().stream()
                // created 行不是孤儿:它只是排队,本进程的 worker 会正常认领。
                .filter(run -> run.status() != AgentRunStatus.CREATED)
                .toList();

        // 多 Worker(任务级所有权)下的关键区分(design §11、§12):启动恢复
        // 绝不终止<b>其他存活实例</b>正在执行的 Run。只有"没有存活 owner"的
        // 非终态 Run 才是真正的孤儿;属于存活租约的 Run 由租约/过期扫描收敛,
        // 不在启动路径上被碰。
        List<AgentRun> orphans = nonTerminal;
        int skippedForLiveOwners = 0;
        if (runtimeTasks != null) {
            Set<UUID> liveOwned = runtimeTasks.workspaceRunsWithLiveOwner();
            orphans = nonTerminal.stream()
                    .filter(run -> !liveOwned.contains(run.id()))
                    .toList();
            skippedForLiveOwners = nonTerminal.size() - orphans.size();
            if (skippedForLiveOwners > 0) {
                LOG.info("Startup orphan recovery skipped {} non-terminal runs that are "
                        + "actively leased by other workers; their leases own the outcome",
                        skippedForLiveOwners);
            }
        }

        int recovered = 0;
        int failed = 0;
        for (AgentRun orphan : orphans) {
            try {
                AgentRunFailureService.FailureOutcome outcome = txTemplate.execute(tx ->
                        terminalizeInterrupted(orphan.id()));
                // 只有状态转换真正生效(APPLIED)或已被所有者收敛
                // (ALREADY_TERMINAL)才算恢复;所有权拒绝计入失败,
                // 绝不把"没有发生的恢复"计入成功数量。
                if (outcome == AgentRunFailureService.FailureOutcome.APPLIED
                        || outcome == AgentRunFailureService.FailureOutcome.ALREADY_TERMINAL) {
                    recovered++;
                } else {
                    failed++;
                }
            } catch (RuntimeException ex) {
                failed++;
                LOG.warn("Orphan run recovery failed for run {}: {}", orphan.id(),
                        ex.getClass().getSimpleName());
            }
        }
        return new OrphanRecoveryResult(recovered, failed, orphans.size());
    }

    /** 一次孤儿恢复的结果:recovered + failed 之和等于发现的孤儿总数。 */
    public record OrphanRecoveryResult(int recovered, int failed, int found) {
        public boolean hasPartialFailure() {
            return failed > 0;
        }
    }
    /**
     * 诚实终态化单个孤儿。状态转换与 RUN_FAILED 事件由失败服务在同一个
     * REQUIRES_NEW 事务内原子提交——状态未生效时不会追加"本次失败已
     * 发生"的事件,也不会把未发生的恢复计入成功。
     */
    private AgentRunFailureService.FailureOutcome terminalizeInterrupted(java.util.UUID runId) {
        return failureService.fail(runId, INTERRUPTED_TRACE,
                RunFailureReasons.INTERRUPTED_BY_RESTART);
    }
}

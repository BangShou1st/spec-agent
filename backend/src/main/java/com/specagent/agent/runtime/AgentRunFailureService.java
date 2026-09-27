package com.specagent.agent.runtime;

import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 文件名:AgentRunFailureService.java
 *
 * 用途:在独立事务中把 AgentRun 标记为 FAILED 终态,并保证"状态转换、
 * RUN_FAILED 业务事件"的原子一致性(第四轮复核的失败一致性闭合)。
 *
 * 事务边界很关键:即使外层的 agent 周期流程抛异常,失败的 run 也必须保持可查询。
 * 若不使用 REQUIRES_NEW 独立事务,包裹它的事务回滚时会把 FAILED 状态更新连同
 * run 创建一起回滚,导致失败记录丢失。
 *
 * <strong>结果分类(不再丢弃条件 UPDATE 的行数)。</strong>fail 返回
 * {@link FailureOutcome},调用方据此决定后续行为:
 * <ul>
 *   <li>{@link FailureOutcome#APPLIED}:条件更新生效(1 行),本次失败
 *       确实发生——RUN_FAILED 业务事件在<strong>同一 REQUIRES_NEW 事务
 *       内</strong>追加,状态与事件要么同时提交要么同时丢失;</li>
 *   <li>{@link FailureOutcome#ALREADY_TERMINAL}:run 已被(当前所有者)
 *       终态化,本次是幂等 no-op——不追加事件,绝不覆盖新所有者的状态、
 *       原因与恢复动作;</li>
 *   <li>{@link FailureOutcome#OWNERSHIP_REFUSED}:本执行器已知丢锁(闩锁),
 *       或锁+代次验证失败(接管已发生/正在发生)——不写任何业务状态、
 *       不追加事件;调用方只能记录本地诊断。</li>
 * </ul>
 *
 * <strong>所有权协议(第四轮 R4-A/R4-C)。</strong>持有租约时,失败
 * 终态化在 REQUIRES_NEW 事务内先对 executor_ownership 行取 FOR SHARE 并
 * 验证代次——与接管的代次递增互斥。FOR SHARE 与外层业务事务持有的
 * FOR SHARE 兼容(共享锁之间不等待),因此"外层业务事务 + 内层
 * REQUIRES_NEW 失败终态化"不构成自死锁。
 */
@Service
public class AgentRunFailureService {

    private final AgentRunRepository agentRunRepository;
    private final ExecutionFence executionFence;
    private final AgentRunEventService eventService;

    public AgentRunFailureService(AgentRunRepository agentRunRepository,
                                  ExecutionFence executionFence,
                                  AgentRunEventService eventService) {
        this.agentRunRepository = agentRunRepository;
        this.executionFence = executionFence;
        this.eventService = eventService;
    }

    /** 一次失败终态化的结果分类。 */
    public enum FailureOutcome {
        /** 条件更新生效:本次失败确实发生,事件已在同一事务内追加。 */
        APPLIED,
        /** run 已是终态:幂等 no-op,不追加事件,不覆盖任何现有终态。 */
        ALREADY_TERMINAL,
        /** 所有权拒绝(已知丢锁或接管已发生):未写任何业务状态。 */
        OWNERSHIP_REFUSED
    }

    /**
     * 失败终态化 + RUN_FAILED 事件的原子提交。仅当状态转换真正生效
     * (APPLIED)时才追加事件;状态未生效时绝不追加宣称"本次失败已发生"
     * 的业务事件。
     *
     * @param runId  目标 run
     * @param trace  写入 run 行的 trace(调用方的失败链路,如 "failed:xxx")
     * @param reason 稳定失败码(RUN_FAILED payload 的 reason)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FailureOutcome fail(UUID runId, String trace, String reason) {
        return doFail(runId, trace, reason, RunFailureReasons.payload(reason));
    }

    /**
     * {@link #fail(UUID, String, String)} 的异常重载:失败码由异常统一归一,
     * payload 使用异常感知版本(为"回答不完整"等失败附加恢复定位信息)。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FailureOutcome fail(UUID runId, String trace, RuntimeException ex) {
        String reason = RunFailureReasons.reasonCode(ex);
        return doFail(runId, trace, reason, RunFailureReasons.payload(ex));
    }

    private FailureOutcome doFail(UUID runId, String trace, String reason,
                                  java.util.Map<String, Object> payload) {
        long epoch;
        try {
            epoch = executionFence.lockOwnershipForWrite();
        } catch (ExecutorLease.LeaseLostException lost) {
            // 已知丢锁/接管已发生:业务解释交由当前合法所有者。
            return FailureOutcome.OWNERSHIP_REFUSED;
        }
        int rows = agentRunRepository.fail(runId, trace, epoch);
        if (rows == 1) {
            eventService.append(runId, AgentRunPhase.FAILED, "RUN_FAILED", payload);
            return FailureOutcome.APPLIED;
        }
        AgentRun current = agentRunRepository.findById(runId).orElse(null);
        if (current != null && current.status().isTerminal()) {
            return FailureOutcome.ALREADY_TERMINAL;
        }
        // 非终态却 0 行:条件被所有权拒绝(罕见竞态),诚实上报为拒绝。
        return FailureOutcome.OWNERSHIP_REFUSED;
    }
}

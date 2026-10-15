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

    /**
     * 失败终态化的执行方式(design §6.3 点名的自等待风险)。
     *
     * <p><b>单执行器模式</b>:沿用 {@code REQUIRES_NEW}。它之所以不自死锁,
     * 靠的是 {@code executor_ownership} 上的 <b>FOR SHARE</b> 与外层共享锁
     * 互相兼容——共享锁之间不等待。这是历史协议,行为保持不变。
     *
     * <p><b>任务级模式(多 Worker)</b>:{@link com.specagent.runtime.execution.TaskWriteGuard}
     * 对 {@code runtime_tasks} 行取的是<b>排他</b>锁。若外层业务事务已持有该行
     * 锁,内层 {@code REQUIRES_NEW}(另一条连接)再取同一行就是自等待——排他
     * 锁与自身不兼容。因此任务级模式改用<b>单条短超时语句</b>设置
     * {@code lock_timeout}:正常情况下失败路径运行时外层执行事务已经结束/回滚
     * (周期服务的执行事务在内层模板里收敛),锁是空闲的;万一外层仍持有该行,
     * 内层会在超时后立即失败并如实上报 {@code OWNERSHIP_REFUSED},由当前合法
     * 所有者按租约收敛——绝不无限期挂住。
     */
    private static final String FAILURE_LOCK_TIMEOUT = "1500ms";

    /**
     * 事务内设置锁超时的端口(仅任务级模式使用)。用 setter 注入而不是构造器,
     * 保持既有 3 参构造器(测试直接 new 的场景)不变。
     */
    private org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setJdbc(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 任务级模式下把本事务的锁等待上限压到很短(design §6.3)。
     *
     * <p>正常情况下失败路径运行时外层执行事务已经结束/回滚,任务行锁是空闲的;
     * 万一外层仍持有该行(排他锁与自身不兼容),这里会在超时后立即失败并如实
     * 上报 {@code OWNERSHIP_REFUSED},由当前合法所有者按租约收敛——绝不无限期
     * 挂住整个执行线程。
     */
    private void applyTaskLevelLockTimeout() {
        if (jdbc == null || !executionFence.multiWorker()) {
            return;
        }
        try {
            jdbc.getJdbcTemplate().execute("SET LOCAL lock_timeout = '" + FAILURE_LOCK_TIMEOUT + "'");
        } catch (RuntimeException ignored) {
            // 设置失败不改变协议:最坏情况退化为普通锁等待,由租约与
            // statement_timeout 兜底。
        }
    }

    /** 从执行线程取当前 token(任务级模式的所有权来源)。 */
    private com.specagent.runtime.execution.ExecutionToken currentToken() {
        return com.specagent.runtime.execution.ExecutionTokenContext.current().orElse(null);
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
        applyTaskLevelLockTimeout();
        long epoch;
        try {
            epoch = executionFence.lockOwnershipForWrite();
        } catch (ExecutorLease.LeaseLostException lost) {
            // 已知丢锁/接管已发生:业务解释交由当前合法所有者。
            return FailureOutcome.OWNERSHIP_REFUSED;
        } catch (com.specagent.runtime.execution.TaskOwnershipLostException lost) {
            // 任务级模式:token 缺失或已被接管。旧执行器绝不写入失败终态——
            // 业务恢复属于当前合法所有者(design §7)。
            return FailureOutcome.OWNERSHIP_REFUSED;
        } catch (com.specagent.access.AccessDeniedException revoked) {
            return FailureOutcome.OWNERSHIP_REFUSED;
        } catch (org.springframework.dao.PessimisticLockingFailureException lockTimeout) {
            // 任务级模式下的锁超时:外层执行事务仍持有任务行排他锁。诚实上报
            // 拒绝而不是挂住;当前所有者会按租约与终态收敛该 Run。
            return FailureOutcome.OWNERSHIP_REFUSED;
        } catch (org.springframework.dao.DataAccessException dbUnavailable) {
            // 数据库不可达:绝不假装失败已生效。
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

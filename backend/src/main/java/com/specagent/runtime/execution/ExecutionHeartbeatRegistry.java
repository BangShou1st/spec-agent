package com.specagent.runtime.execution;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * 文件名:ExecutionHeartbeatRegistry.java
 *
 * 用途:登记<b>本进程当前正在执行</b>的任务 token,供独立心跳执行器按间隔续租
 * (docs/v2/PHASE1_MULTI_WORKER_EXECUTION_DESIGN.md §6.4),并把"已知失去所有权"
 * 信号接入真实执行链。
 *
 * <p><b>一律按完整 token(taskId + epoch + leaseId + workerId)绑定。</b>这样:
 * <ul>
 *   <li>旧 attempt 的迟到心跳结果不会误标记新 attempt;</li>
 *   <li>旧 attempt 的 finally 清理不会摘掉新 attempt 的登记;</li>
 *   <li>失权标记只对产生它的那次执行有效,重试/接管后的新 token 不受影响。</li>
 * </ul>
 *
 * <p>登记项与失权标记都只是<b>声明性</b>的本进程内存事实:是否真的仍然拥有
 * 任务,永远以数据库行上的条件续租/写入护栏为唯一权威。内存标记只用于"本进程
 * 已知自己失权"时<b>立即停止</b>发起新步骤,绝不替代数据库校验。
 */
@Component
public class ExecutionHeartbeatRegistry {

    private final ConcurrentMap<UUID, ExecutionToken> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ExecutionToken> ownershipLost = new ConcurrentHashMap<>();

    /** 认领后按 token 登记;同一 token 上一次可能留下的失权标记随之清除。 */
    public void register(ExecutionToken token) {
        inFlight.put(token.taskId(), token);
        ownershipLost.remove(token.taskId(), token);
    }

    /**
     * 按 token 注销。<b>只清理与给定 token 相同的登记与失权标记</b>:旧 attempt
     * 的 finally 绝不摘掉新 attempt 的登记,旧心跳结果也绝不污染新 attempt。
     * 重复注销是幂等 no-op。
     */
    public void unregister(ExecutionToken token) {
        inFlight.remove(token.taskId(), token);
        ownershipLost.remove(token.taskId(), token);
    }

    /**
     * 心跳续租失败即判定失去所有权:摘除该 token 的登记并置位,不再续租——
     * <b>绝不复活过期租约</b>。执行链的下一步骤由护栏立即拒绝;数据库写入仍是
     * 最终权威。
     */
    public void markOwnershipLost(ExecutionToken token) {
        inFlight.remove(token.taskId(), token);
        ownershipLost.put(token.taskId(), token);
    }

    /** 仅当失权标记属于<b>同一个</b> token(同一次执行)时才为真。 */
    public boolean isOwnershipLost(ExecutionToken token) {
        if (token == null) {
            return false;
        }
        return token.equals(ownershipLost.get(token.taskId()));
    }

    /**
     * 按<b>执行身份</b>(taskId + epoch + 绑定 lease)查询本进程是否已判定
     * 失权。供不带 ThreadLocal 的线程使用(内部 RPC、StreamingResponseBody
     * 异步流式线程):它们没有执行 token,但请求携带的 epoch 与 GA 绑定 lease
     * 足以精确寻址到同一次执行——失权标记只对产生它的那次执行为真,旧
     * attempt 的失权绝不影响新 attempt。
     *
     * <p>绑定 lease 与认领 lease 在 GA 执行中是两个值(执行信封携带绑定
     * lease,任务行持有认领 lease),因此这里只按 epoch 精确匹配:同任务行
     * 上同 epoch 即同一次认领;跨 attempt(接管后 epoch 递增)永不匹配。
     */
    public boolean isOwnershipLostByEpochAndLease(UUID taskId, long epoch, UUID leaseId) {
        if (taskId == null || epoch < 1) {
            return false;
        }
        ExecutionToken lost = ownershipLost.get(taskId);
        return lost != null && lost.epoch() == epoch;
    }

    /** 消费一次"执行途中已失去所有权"的事实(用于避免声明虚假成功);按 token 绑定。 */
    public boolean consumeOwnershipLost(ExecutionToken token) {
        return ownershipLost.remove(token.taskId(), token);
    }

    /** 当前登记的执行 token 快照。 */
    public List<ExecutionToken> inFlightTokens() {
        return List.copyOf(inFlight.values());
    }
}

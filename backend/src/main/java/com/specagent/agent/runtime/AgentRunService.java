package com.specagent.agent.runtime;

import com.specagent.agent.runtime.LoopLinkage;
import com.specagent.common.Ids;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:AgentRunService.java
 *
 * 用途:AgentRun 的应用服务,负责 run 的创建与状态推进。支持客户端幂等创建:
 * 以 (projectId, idempotencyKey) 为唯一标识,配合请求指纹决定"重放已存在的 run"
 * 还是"拒绝 key 复用",由数据库唯一索引裁决并发竞争。
 *
 * AgentRun 本身不持有持久化的需求状态;runtime 内核仍是唯一事实来源,
 * 本服务只落库 run 元数据及 run 产出记录的 id 引用。
 *
 * 执行器 fencing(第三轮复核 R3-A 的闭合):所有权校验与写入不是两次
 * 独立的数据库往返,而是同一协议——run 的持久化检查点与终态写入由仓储层
 * 以单条条件 UPDATE 完成,在<strong>同一条语句内</strong>验证全局所有权
 * 代次仍等于本执行器代次(携带 {@link ExecutionFence#ownerEpoch()} 的
 * fencing token)且 run 未终态。因此线程在检查后暂停任意时间都不会让旧
 * 执行器的写入通过:新执行器接管(代次递增)一提交,旧执行器的条件写入
 * 即落空,本服务以 {@link ExecutorLease.LeaseLostException} 拒绝,调用方
 * 事务据此回滚(业务产物写入与检查点在同一事务,产物一并回滚)。
 *
 * fail() 是有条件的原子终态化:只允许把非终态的 run 置为 FAILED;终态
 * (COMPLETED/FAILED,含新所有者写入的失败原因)绝不被覆盖。落空(0 行)
 * 是幂等 benign——重复失败上报、已被新所有者终态化都表现为 no-op。丢锁
 * 的旧执行器 fail 落空后只记录本地故障,业务恢复由当前合法所有者负责。
 * 没有执行器租约的进程(API-only、测试驱动)保持历史写入行为。
 */
@Service
public class AgentRunService {

    private final AgentRunRepository agentRunRepository;
    private final ExecutionFence executionFence;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    public AgentRunService(AgentRunRepository agentRunRepository,
                           ExecutionFence executionFence,
                           org.springframework.transaction.support.TransactionTemplate transactionTemplate) {
        this.agentRunRepository = agentRunRepository;
        this.executionFence = executionFence;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 受保护检查点/终态写入的统一事务包装(第四轮 R4-A/R4-C 协议)。
     *
     * 持有租约时(epoch > 0),写入在一个数据库事务内完成:事务的第一条
     * 语句由 {@link ExecutionFence#lockOwnershipForWrite()} 对所有权行取
     * FOR SHARE 并验证代次,锁保持到提交——接管在本事务结束前无法提交
     * 新代次,写入的快照子查询因此不可能读到过期代次通过条件。调用方
     * 已有事务时(REQUIRED 传播)自然并入同一事务,锁复用,语义不变;
     * 无事务时(自动提交检查点)本包装给出等价的短事务保护。
     *
     * 无租约进程(epoch == 0)不包事务、不取锁,保持历史写入行为。
     */
    private int fencedWrite(java.util.function.Supplier<Integer> write, UUID runId) {
        long epoch = ownerEpoch();
        if (epoch == 0) {
            int rows = write.get();
            requireUpdated(rows, runId);
            return rows;
        }
        Integer rows = transactionTemplate.execute(tx -> {
            executionFence.lockOwnershipForWrite();
            return write.get();
        });
        requireUpdated(rows == null ? 0 : rows, runId);
        return rows == null ? 0 : rows;
    }

    /**
     * 本进程写入 run 行携带的所有权代次;0 表示无租约(历史行为)。
     * 已闩锁丢失时抛出 {@link ExecutorLease.LeaseLostException}(R4-C)。
     */
    private long ownerEpoch() {
        return executionFence.ownerEpoch();
    }

    /**
     * 检查点/终态条件写入的结果:租约存在时代次为 0 行意味着所有权条件
     * 拒绝——或因接管后全局代次已变(丢锁),或 run 已被终态化。两种情况
     * 都不允许本执行器继续提交,抛出 {@link ExecutorLease.LeaseLostException}
     * 让调用方事务回滚(业务产物与检查点同事务,产物一并回滚)。
     */
    private void requireUpdated(int rows, UUID runId) {
        if (ownerEpoch() > 0 && rows == 0) {
            throw new ExecutorLease.LeaseLostException(
                    "conditional run write updated 0 rows for run " + runId
                            + "; ownership was taken over or the run is terminal");
        }
    }

    public AgentRun create(UUID projectId,
                           UUID routeId,
                           AgentRunTriggerType triggerType,
                           UUID inputNodeId,
                           UUID createdByRunId) {
        return create(projectId, routeId, triggerType, inputNodeId, createdByRunId, null);
    }

    public AgentRun create(UUID projectId,
                           UUID routeId,
                           AgentRunTriggerType triggerType,
                           UUID inputNodeId,
                           UUID createdByRunId,
                           String operation) {
        return create(projectId, routeId, triggerType, inputNodeId, createdByRunId, operation, null);
    }

    /**
     * 遗留的非幂等 run 创建入口。客户端幂等的变更路径必须改用
     * {@link #createWithIdempotency} 并携带规范化请求指纹。
     */
    public AgentRun create(UUID projectId,
                           UUID routeId,
                           AgentRunTriggerType triggerType,
                           UUID inputNodeId,
                           UUID createdByRunId,
                           String operation,
                           String idempotencyKey) {
        return createWithIdempotency(projectId, routeId, triggerType, inputNodeId,
                createdByRunId, operation, idempotencyKey, null).run();
    }

    /**
     * 在调用方读取可变图状态之前,先解析幂等请求是否已落库。指纹匹配则重放
     * 已存在的 run;同一 project/key 但指纹不匹配的请求被拒绝。
     */
    public Optional<AgentRun> findIdempotentReplay(UUID projectId,
                                                    String idempotencyKey,
                                                    String requestFingerprint) {
        String normalizedKey = normalizeKey(idempotencyKey);
        if (normalizedKey == null) {
            return Optional.empty();
        }
        if (requestFingerprint == null || requestFingerprint.isBlank()) {
            throw new IllegalArgumentException(
                    "Client-idempotent agent runs require a request fingerprint");
        }
        Optional<AgentRun> existing = agentRunRepository
                .findByProjectIdAndIdempotencyKey(projectId, normalizedKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        if (requestFingerprint.equals(existing.get().requestFingerprint())) {
            return existing;
        }
        throw new IdempotencyKeyReusedException();
    }

    /**
     * 创建 run,并返回本调用方是否是实际插入者。对客户端幂等请求,由数据库
     * 裁决 project/key 竞争:指纹匹配时返回已落库的"胜出"run,指纹不匹配
     * 则确定性地判定为 key 复用冲突。
     */
    public CreateResult createWithIdempotency(UUID projectId,
                                              UUID routeId,
                                              AgentRunTriggerType triggerType,
                                              UUID inputNodeId,
                                              UUID createdByRunId,
                                              String operation,
                                              String idempotencyKey,
                                              String requestFingerprint) {
        return createWithIdempotency(projectId, routeId, triggerType, inputNodeId,
                createdByRunId, operation, idempotencyKey, requestFingerprint,
                LoopLinkage.none());
    }

    /**
     * {@link #createWithIdempotency(UUID, UUID,
     * AgentRunTriggerType, UUID, UUID, String, String, String)} 的 loop 链路变体,
     * 额外携带续跑链路 {@code linkage}。
     *
     * {@code createdByRunId} 创建提示保持遗留语义(不落库),并与
     * {@code linkage} 保持独立:前者记录"谁发起的",后者记录"哪个终止边界
     * 派生出本 run"。两者概念绝不能合并。
     */
    public CreateResult createWithIdempotency(UUID projectId,
                                              UUID routeId,
                                              AgentRunTriggerType triggerType,
                                              UUID inputNodeId,
                                              UUID createdByRunId,
                                              String operation,
                                              String idempotencyKey,
                                              String requestFingerprint,
                                              LoopLinkage linkage) {
        UUID runId = Ids.random();
        Instant now = Instant.now();
        String normalizedKey = normalizeKey(idempotencyKey);
        if (normalizedKey != null && (requestFingerprint == null || requestFingerprint.isBlank())) {
            throw new IllegalArgumentException(
                    "Client-idempotent agent runs require a request fingerprint");
        }
        LoopLinkage effectiveLinkage = linkage == null ? LoopLinkage.none() : linkage;
        AgentRun run = new AgentRun(runId, projectId, routeId, triggerType, inputNodeId, null,
                null, null, null, null, AgentRunStatus.CREATED, null, operation,
                normalizedKey, normalizedKey == null ? null : requestFingerprint, now, null,
                effectiveLinkage.parentRunId(), effectiveLinkage.rootRunId(),
                effectiveLinkage.cycleIndex());
        if (normalizedKey == null) {
            agentRunRepository.save(run);
            return new CreateResult(run, true);
        }

        try {
            if (agentRunRepository.insertIfAbsent(run)) {
                return new CreateResult(run, true);
            }
        } catch (DuplicateKeyException raced) {
            // loop 链路的续跑子 run 存在两道唯一性兜底,可能同时竞争:
            // 一是 project 范围的幂等键(上面的 ON CONFLICT 已覆盖),
            // 二是 V23 的 parent_run_id 单子索引(未被覆盖——同一父 run 的
            // 子行可能先由兄弟事务提交)。只有 loop 链路场景允许在下面恢复;
            // 普通幂等创建直接重新抛出,确保不吞掉任何无关的唯一性失败。
            if (effectiveLinkage.parentRunId() == null) {
                throw raced;
            }
        }

        AgentRun existing = agentRunRepository
                .findByProjectIdAndIdempotencyKey(projectId, normalizedKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotent agent-run row missing after insert race"));
        if (requestFingerprint.equals(existing.requestFingerprint())) {
            if (effectiveLinkage.parentRunId() != null) {
                verifyContinuationWinner(effectiveLinkage, normalizedKey,
                        requestFingerprint, existing);
            }
            return new CreateResult(existing, false);
        }
        throw new IdempotencyKeyReusedException();
    }

    /**
     * 对 loop 链路插入竞争中的"失败方"做 fail-closed 校验:重新加载出的胜出行
     * 必须是同一父 run、同一确定性 key 与指纹下创建的子 run。同父但不同 key、
     * 或同 key 但不同父的行,绝不能被当作成功别名。
     */
    private void verifyContinuationWinner(LoopLinkage linkage,
                                          String normalizedKey,
                                          String requestFingerprint,
                                          AgentRun existing) {
        if (!linkage.parentRunId().equals(existing.parentRunId())
                || !normalizedKey.equals(existing.idempotencyKey())
                || !requestFingerprint.equals(existing.requestFingerprint())) {
            throw new IllegalStateException(
                    "Continuation child race resolved to a different chain row: "
                            + "expected parent " + linkage.parentRunId()
                            + " under key " + normalizedKey);
        }
    }

    private String normalizeKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return idempotencyKey.trim();
    }

    public void complete(UUID runId, AgentRunStatus status, String trace) {
        fencedWrite(() -> agentRunRepository.updateStatus(runId, status, Instant.now(), trace, ownerEpoch()), runId);
    }

    public void attachContext(UUID runId, UUID contextSnapshotId, String trace) {
        fencedWrite(() -> agentRunRepository.attachContext(runId, contextSnapshotId, trace, ownerEpoch()), runId);
    }

    public void markModelCalled(UUID runId, String trace) {
        fencedWrite(() -> agentRunRepository.markModelCalled(runId, trace, ownerEpoch()), runId);
    }

    public void markReflected(UUID runId, String trace) {
        fencedWrite(() -> agentRunRepository.markReflected(runId, trace, ownerEpoch()), runId);
    }

    public void markPersistedNode(UUID runId, UUID producedNodeId, String trace) {
        fencedWrite(() -> agentRunRepository.markPersistedNode(runId, producedNodeId, trace, ownerEpoch()), runId);
    }

    public void markPersistedAnswer(UUID runId, UUID producedAnswerId, String trace) {
        fencedWrite(() -> agentRunRepository.markPersistedAnswer(runId, producedAnswerId, trace, ownerEpoch()), runId);
    }

    public void markPersistedAnswerPatch(UUID runId, UUID producedPatchId, String trace) {
        fencedWrite(() -> agentRunRepository.markPersistedAnswerPatch(runId, producedPatchId, trace, ownerEpoch()), runId);
    }

    public void markPersistedSpecSnapshot(UUID runId, UUID producedSpecSnapshotId, String trace) {
        fencedWrite(() -> agentRunRepository.markPersistedSpecSnapshot(runId, producedSpecSnapshotId, trace, ownerEpoch()), runId);
    }

    /**
     * 有条件的原子失败终态化。落空(0 行)是幂等 benign:重复失败上报、
     * 已被当前所有者终态化、或本执行器已丢锁(全局代次已变)都表现为
     * no-op——绝不覆盖终态。已闩锁丢失时 {@link #ownerEpoch()} 立即抛出
     * (丢锁后连诚实终态化也不再写入,业务恢复由当前合法所有者负责);
     * RUN_FAILED 业务事件由 {@link AgentRunFailureService} 在状态转换
     * 真正生效的事务内追加,本方法绝不追加事件。
     */
    public void fail(UUID runId, String trace) {
        long epoch = ownerEpoch();
        if (epoch == 0) {
            agentRunRepository.fail(runId, trace, 0);
            return;
        }
        transactionTemplate.executeWithoutResult(tx -> {
            executionFence.lockOwnershipForWrite();
            agentRunRepository.fail(runId, trace, epoch);
            // 0 行是幂等 benign(重复上报/已被终态化),不抛出。
        });
    }

    public Optional<AgentRun> getRun(UUID runId) {
        return agentRunRepository.findById(runId);
    }

    public java.util.List<AgentRun> listByProject(UUID projectId) {
        return agentRunRepository.findByProject(projectId);
    }

    /** 项目内全部非终态 run,按创建顺序排列。 */
    public java.util.List<AgentRun> listActiveByProject(UUID projectId) {
        return agentRunRepository.findActiveByProject(projectId);
    }

    /** 持久化了指定 Answer 的全部 run,按创建顺序排列。 */
    public java.util.List<AgentRun> findByProducedAnswerId(UUID answerId) {
        return agentRunRepository.findByProducedAnswerId(answerId);
    }

    public record CreateResult(AgentRun run, boolean inserted) {
    }
}

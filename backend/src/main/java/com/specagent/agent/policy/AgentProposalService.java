package com.specagent.agent.policy;

import com.specagent.agent.protocol.ActionProposal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:AgentProposalService.java
 *
 * 用途:动作提案生命周期的服务层管理:创建、接受、拒绝与过期。
 * 每次状态流转都通过 decidedAt/decidedBy 时间戳留下可追溯的审计记录。
 *
 * 流转语义是"单胜者":终态转换基于数据库的原子 compare-and-set,
 * 并发竞争时只有一个请求成功,其余抛 {@link ProposalAlreadyDecidedException},
 * 绝不覆盖已有终态。接受前会用行锁(requirePending)快速失败于已决定的提案。
 *
 * 协作:被 AgentProposalController(用户接受/拒绝)与
 * ProposalAcceptanceService(接受后执行)调用;底层持久化在
 * {@link AgentProposalRepository}。
 */
@Service
public class AgentProposalService {

    private final AgentProposalRepository repository;

    public AgentProposalService(AgentProposalRepository repository) {
        this.repository = repository;
    }

    /**
     * 以 PROPOSED 状态创建新提案。幂等性由数据库唯一索引保证:当相同键的
     * 提案已存在时——包括被其他 worker 并发插入的那条——原样返回已存在的
     * 提案,数据库中永远只有一行。
     */
    @Transactional
    public AgentProposal createProposal(ActionProposal actionProposal,
                                        UUID runId, UUID projectId, UUID routeId) {
        AgentProposal proposal = new AgentProposal(
                actionProposal.proposalId(),
                runId, projectId, routeId,
                actionProposal.actionFamily(),
                actionProposal.payload(),
                actionProposal.anchorRefs(),
                ProposalStatus.PROPOSED,
                actionProposal.baseContextSnapshotId(),
                actionProposal.baseContextHash(),
                actionProposal.idempotencyKey(),
                Instant.now(), null, null);
        if (!repository.insertIfAbsent(proposal)) {
            // 插入竞争落败:已持久化的胜者是共享的唯一真相。
            return repository.findByIdempotencyKey(actionProposal.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotent proposal row missing after losing its insert race: "
                                    + actionProposal.idempotencyKey()));
        }
        return proposal;
    }

    /**
     * 接受提案,将其标记为 ACCEPTED 并记录当前时间。从 PROPOSED 出发的
     * 转换是原子且单胜者的:提案已被决定(被并发请求 ACCEPTED/REJECTED/
     * EXPIRED)时抛 {@link ProposalAlreadyDecidedException},
     * 而不是覆盖终态。
     */
    @Transactional
    public void acceptProposal(UUID proposalId, String decidedBy) {
        requirePending(proposalId);
        if (!repository.transitionFromProposed(proposalId, ProposalStatus.ACCEPTED,
                Instant.now(), decidedBy)) {
            throw alreadyDecided(proposalId);
        }
    }

    /**
     * 拒绝提案,将其标记为 REJECTED 并记录当前时间。
     * 与接受相同的单胜者规则:绝不覆盖已有的终态。
     */
    @Transactional
    public void rejectProposal(UUID proposalId, String decidedBy) {
        requirePending(proposalId);
        if (!repository.transitionFromProposed(proposalId, ProposalStatus.REJECTED,
                Instant.now(), decidedBy)) {
            throw alreadyDecided(proposalId);
        }
    }

    /**
     * 使提案过期,将其标记为 EXPIRED 并记录当前时间。
     * 与接受相同的单胜者规则:绝不覆盖已有的终态。
     */
    @Transactional
    public void expireProposal(UUID proposalId) {
        requirePending(proposalId);
        if (!repository.transitionFromProposed(proposalId, ProposalStatus.EXPIRED,
                Instant.now(), "system")) {
            throw alreadyDecided(proposalId);
        }
    }

    /**
     * 锁定提案行,在提案已决定或不存在时快速失败。锁持有到事务结束,
     * 因此同一提案的并发决策会在这里排队,并在自己的 CAS 尝试之前
     * 重新读取已提交的状态。
     */
    private void requirePending(UUID proposalId) {
        AgentProposal locked = repository.findByIdForUpdate(proposalId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Proposal not found: " + proposalId));
        if (locked.status() != ProposalStatus.PROPOSED) {
            throw new ProposalAlreadyDecidedException(locked.status().code());
        }
    }

    /**
     * 在 CAS 观察到影响行数为 0 后构造落败方异常。状态是重新读取的
     * (READ_COMMITTED 下胜者的行此时已提交),因此报告的状态与数据库一致。
     */
    private ProposalAlreadyDecidedException alreadyDecided(UUID proposalId) {
        String currentStatus = repository.findById(proposalId)
                .map(p -> p.status().code())
                .orElse("UNKNOWN");
        return new ProposalAlreadyDecidedException(currentStatus);
    }

    /**
     * 供生命周期决策使用的加锁读取。即将代表 PROPOSED 提案执行工作的
     * 调用方(接受事务)必须提前取行锁,使锁覆盖其整个事务。
     */
    public Optional<AgentProposal> getProposalForUpdate(UUID id) {
        return repository.findByIdForUpdate(id);
    }

    public Optional<AgentProposal> getProposal(UUID id) {
        return repository.findById(id);
    }

    /**
     * 查找由指定 agent run 创建的提案(若有)。把变更动作降级为待批准的
     * node-query run 恰好产出一条以 {@code runId} 关联的提案;
     * 只读 run 从不创建提案。
     */
    public Optional<AgentProposal> findByRunId(UUID runId) {
        return repository.findByRunId(runId);
    }

    public List<AgentProposal> getPendingProposals(UUID projectId) {
        return repository.findByProjectAndStatus(projectId, ProposalStatus.PROPOSED);
    }

    public List<AgentProposal> getByStatus(UUID projectId, ProposalStatus status) {
        return repository.findByProjectAndStatus(projectId, status);
    }
}

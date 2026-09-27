package com.specagent.workspace.patch;

import com.specagent.common.Ids;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:AnswerPatchService.java
 *
 * 用途:持久化由答案推导出的不可变 answer patch。
 *
 * 一条 answer patch 携带领域中立的 {@link Claim}。沿活跃路线 lineage
 * 重放这些 patch 即可推导出需求状态。patch 是由运行时写入的记录;
 * 本服务不在这里调用模型生成。
 */
@Service
public class AnswerPatchService {

    private final AnswerPatchRepository answerPatchRepository;
    private final AnswerPatchIndexPort answerPatchIndexPort;

    public AnswerPatchService(AnswerPatchRepository answerPatchRepository,
                              AnswerPatchIndexPort answerPatchIndexPort) {
        this.answerPatchRepository = answerPatchRepository;
        this.answerPatchIndexPort = answerPatchIndexPort;
    }

    public AnswerPatch save(UUID projectId,
                            UUID routeId,
                            UUID sourceNodeId,
                            UUID sourceAnswerId,
                            List<Claim> claims,
                            UUID createdByRunId) {
        Optional<AnswerPatch> existing = findBySourceAnswerId(sourceAnswerId);
        if (existing.isPresent()) {
            throw new IllegalStateException(
                    "Answer already has a persisted patch: " + sourceAnswerId);
        }
        UUID patchId = Ids.random();
        Instant now = Instant.now();
        AnswerPatch patch = new AnswerPatch(patchId, projectId, routeId, sourceNodeId,
                sourceAnswerId, claims, createdByRunId, now);
        answerPatchRepository.save(patch);
        answerPatchIndexPort.index(patch);
        return patch;
    }

    /**
     * 面向并发恢复尝试的幂等 checkpoint 写入。唯一 source-answer 约束
     * 负责仲裁竞态;输掉的一方复用赢家的不可变 patch,
     * 而不是产生第二个副作用。
     */
    public AnswerPatch saveOrReuse(UUID projectId,
                                   UUID routeId,
                                   UUID sourceNodeId,
                                   UUID sourceAnswerId,
                                   List<Claim> claims,
                                   UUID createdByRunId) {
        Optional<AnswerPatch> existing = findBySourceAnswerId(sourceAnswerId);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return save(projectId, routeId, sourceNodeId, sourceAnswerId, claims, createdByRunId);
        } catch (DuplicateKeyException race) {
            return findBySourceAnswerId(sourceAnswerId)
                    .orElseThrow(() -> race);
        }
    }

    public List<AnswerPatch> findByRoute(UUID routeId) {
        return answerPatchRepository.findByRoute(routeId);
    }

    public List<AnswerPatch> findBySourceAnswerIds(List<UUID> answerIds) {
        return answerPatchRepository.findBySourceAnswerIds(answerIds);
    }

    /**
     * 返回一条答案对应的唯一 patch checkpoint;patch 步骤尚未完成时
     * 返回空。多条结果绝不通过 first/latest 回退消解,那会掩盖
     * 正确性违例。
     */
    public Optional<AnswerPatch> findBySourceAnswerId(UUID sourceAnswerId) {
        List<AnswerPatch> patches = answerPatchRepository.findBySourceAnswerId(sourceAnswerId);
        if (patches.size() > 1) {
            throw new IllegalStateException(
                    "Answer has multiple persisted patches: " + sourceAnswerId);
        }
        return patches.stream().findFirst();
    }

    public Optional<AnswerPatch> getPatch(UUID patchId) {
        return answerPatchRepository.findById(patchId);
    }
}

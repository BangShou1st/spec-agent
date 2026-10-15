package com.specagent.agent.runtime;

import com.specagent.runtime.execution.WorkspaceRunStep;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 文件名:SqlAnswerChainFacts.java
 *
 * 用途:{@link WorkspaceAnswerChainRecoveryAdapter.AnswerChainFacts} 的持久化
 * 实现:只读查询 answers / answer_patches,校验结果引用是否真实存在。
 *
 * <p>它刻意只做<b>存在性与唯一性</b>校验,不读取正文——接管判定不需要正文,
 * 而读取正文会把恢复路径变成数据泄露面。
 */
@Component
public class SqlAnswerChainFacts
        implements WorkspaceAnswerChainRecoveryAdapter.AnswerChainFacts {

    private final NamedParameterJdbcTemplate jdbc;

    public SqlAnswerChainFacts(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String danglingReference(WorkspaceRunStep step) {
        String ref = step.resultRef();
        if (ref == null || ref.isBlank()) {
            return "no result reference recorded for a SUCCEEDED step";
        }
        int colon = ref.indexOf(':');
        if (colon <= 0) {
            return "malformed result reference: " + ref;
        }
        String kind = ref.substring(0, colon);
        String idPart = ref.substring(colon + 1);
        UUID id;
        try {
            id = UUID.fromString(idPart);
        } catch (IllegalArgumentException badId) {
            return "result reference is not a UUID: " + ref;
        }
        return switch (kind) {
            case "answer" -> exists("answers", id) ? null : "answer row missing: " + id;
            case "patch" -> exists("answer_patches", id) ? null : "patch row missing: " + id;
            case "node" -> exists("nodes", id) ? null : "node row missing: " + id;
            case "spec" -> exists("spec_snapshots", id) ? null : "spec snapshot missing: " + id;
            // context 快照在部分路径上允许为空(见既有实现),因此不作为硬失败。
            case "context" -> null;
            default -> "unknown result reference kind: " + kind;
        };
    }

    private boolean exists(String table, UUID id) {
        // 表名来自上面的私有白名单分支,绝不来自外部输入。
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE id = :id",
                Map.of("id", id), Integer.class);
        return count != null && count > 0;
    }

    @Override
    public WorkspaceAnswerChainRecoveryAdapter.ChainShape chainShape(UUID runId) {
        // answers 没有 created_by_run_id 列;Run 与产物的权威关联是
        // agent_runs.produced_answer_id / produced_patch_id(既有事实模型)。
        var run = jdbc.queryForList(
                "SELECT produced_answer_id, produced_patch_id FROM agent_runs WHERE id = :runId",
                Map.of("runId", runId));
        if (run.isEmpty()) {
            return new WorkspaceAnswerChainRecoveryAdapter.ChainShape(0, 0, false);
        }
        UUID answerId = (UUID) run.getFirst().get("produced_answer_id");
        UUID patchId = (UUID) run.getFirst().get("produced_patch_id");

        int answerCount = answerId != null && exists("answers", answerId) ? 1 : 0;
        int patchCount = 0;
        if (patchId != null && exists("answer_patches", patchId)) {
            patchCount = 1;
        }

        // 该 Run 名下的 patch 若指向不存在的 answer,说明 checkpoint 与不可变
        // 事实不一致:绝不基于它继续。除 produced_patch_id 之外再按 created_by_run_id
        // 兜底统计,避免"写了 patch 行但没回填 produced_patch_id"的窗口被漏掉。
        Integer orphanPatches = jdbc.queryForObject("""
                SELECT COUNT(*) FROM answer_patches p
                WHERE p.created_by_run_id = :runId
                  AND NOT EXISTS (SELECT 1 FROM answers a WHERE a.id = p.source_answer_id)
                """, Map.of("runId", runId), Integer.class);
        Integer totalPatches = jdbc.queryForObject(
                "SELECT COUNT(*) FROM answer_patches WHERE created_by_run_id = :runId",
                Map.of("runId", runId), Integer.class);
        if (totalPatches != null && totalPatches > patchCount) {
            patchCount = totalPatches;
        }
        return new WorkspaceAnswerChainRecoveryAdapter.ChainShape(
                answerCount, patchCount, orphanPatches != null && orphanPatches > 0);
    }
}

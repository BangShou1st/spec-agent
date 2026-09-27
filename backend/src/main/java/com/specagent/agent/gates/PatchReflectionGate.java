package com.specagent.agent.gates;

import com.specagent.agent.decision.AnswerPatchDraft;
import com.specagent.agent.decision.ReflectionResult;
import com.specagent.workspace.patch.Claim;
import com.specagent.workspace.patch.ClaimStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件名:PatchReflectionGate.java
 *
 * 用途:Answer patch 草稿的确定性校验门禁。
 *
 * confirmed 状态的 claim 必须携带完整来源(sourceNodeId +
 * sourceAnswerId)才允许进入需求状态(requirement state)。assumed 或
 * unresolved 的 claim 目前可以缺少来源,但在 spec grounding 期间
 * 绝不会被当作已确认的 spec claim。
 *
 * 协作:由 Answer 周期的反思阶段调用,拒绝时返回带错误列表的
 * ReflectionResult。
 */
@Component
public class PatchReflectionGate {

    public ReflectionResult validate(AnswerPatchDraft draft) {
        List<String> errors = new ArrayList<>();

        if (draft == null) {
            return ReflectionResult.rejectedResult("Answer patch draft is required");
        }

        for (Claim claim : draft.claims()) {
            if (claim == null) {
                errors.add("Patch draft contains null claim");
                continue;
            }

            if (claim.text() == null || claim.text().isBlank()) {
                errors.add("Claim text is required");
            }

            if (claim.status() == ClaimStatus.CONFIRMED) {
                if (claim.sourceNodeId() == null) {
                    errors.add("Confirmed claim requires sourceNodeId");
                }
                if (claim.sourceAnswerId() == null) {
                    errors.add("Confirmed claim requires sourceAnswerId");
                }
            }
        }

        if (errors.isEmpty()) {
            return ReflectionResult.acceptedResult();
        }
        return new ReflectionResult(false, errors, List.of());
    }
}
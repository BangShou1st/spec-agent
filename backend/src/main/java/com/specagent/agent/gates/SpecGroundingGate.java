package com.specagent.agent.gates;

import com.specagent.agent.decision.ReflectionResult;
import com.specagent.agent.decision.SpecDraft;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件名:SpecGroundingGate.java
 *
 * 用途:spec 草稿的确定性校验门禁。
 *
 * 每个 spec 分节都必须非空并携带来源引用,使生成的 spec 始终
 * grounded 在探索上下文之上。允许存在未解决条目,但它们绝不能替代
 * 分节的来源引用。
 *
 * 协作:由 spec 生成流程调用,位于 SpecSourceReferenceGuard 之前;
 * 拒绝时返回带错误列表的 ReflectionResult。
 */
@Component
public class SpecGroundingGate {

    public ReflectionResult validate(SpecDraft draft) {
        List<String> errors = new ArrayList<>();

        if (draft == null) {
            return ReflectionResult.rejectedResult("Spec draft is required");
        }

        for (String sectionName : draft.sections().keySet()) {
            String content = draft.sections().get(sectionName);
            if (content == null || content.isBlank()) {
                errors.add("Spec section content is required: " + sectionName);
                continue;
            }

            List<String> refs = draft.sourceRefsBySection().get(sectionName);
            if (refs == null || refs.isEmpty()) {
                errors.add("Spec section requires source references: " + sectionName);
            }
        }

        if (errors.isEmpty()) {
            return ReflectionResult.acceptedResult();
        }
        return new ReflectionResult(false, errors, List.of());
    }
}
package com.specagent.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件名:BrainScript.java
 *
 * 用途:脚本化的确定性 Brain 输出,在模型边界处替代生产大模型(B-fast 层)。
 * 包含一组 STATE_UPDATE 声明、一个 DECISION 决策,以及已知/冲突观察列表。
 * {@code canonical()} 把所有声明排序后渲染成规范化字符串,作为分层校验的
 * 确定性基准。
 *
 * 协作:由 {@link ScenarioDefinition} 的脚本配置构造,注入 B-fast 执行链路。
 */
public record BrainScript(
        List<BrainClaim> claims,
        BrainDecision decision,
        List<String> knownObservations,
        List<String> conflictObservations) {

    public BrainScript {
        claims = claims == null ? List.of() : List.copyOf(claims);
        knownObservations = knownObservations == null ? List.of() : List.copyOf(knownObservations);
        conflictObservations =
                conflictObservations == null ? List.of() : List.copyOf(conflictObservations);
    }

    public String canonical() {
        List<String> renderedClaims = new ArrayList<>();
        for (BrainClaim claim : claims) {
            renderedClaims.add(claim.canonical());
        }
        renderedClaims.sort(String::compareTo);
        return "brain" + renderedClaims
                + "[decision(" + decision.actionFamily() + ")"
                + ",known(" + knownObservations.size()
                + "),conflicts(" + conflictObservations.size() + ")]";
    }
}

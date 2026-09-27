package com.specagent.agent.protocol;

import java.util.List;

/**
 * 文件名:ObservationView.java
 *
 * 用途:由决策引擎推导出的结构化观察(已知、未知、冲突、风险),
 * 帮助模型理解当前局面。
 *
 * 约束:仅供推理参考——不是持久化的事实,绝不能绕过确定性的
 * Java 校验器。
 */
public record ObservationView(List<String> known,
                              List<String> unknowns,
                              List<String> conflicts,
                              List<String> risks) {

    public ObservationView {
        known = known == null ? List.of() : List.copyOf(known);
        unknowns = unknowns == null ? List.of() : List.copyOf(unknowns);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        risks = risks == null ? List.of() : List.copyOf(risks);
    }
}

package com.specagent.agent.protocol;

import java.util.List;
import java.util.UUID;

/**
 * 文件名:ProposedClaim.java
 *
 * 用途:Brain 提出的单条 claim(断言),携带内容、状态以及为其
 * 提供依据的 source refs。
 *
 * 约束:绝不携带 Runtime 独有的 claim id;id 由 Java 侧在校验
 * 通过后分配。
 */
public record ProposedClaim(String kind,
                            String text,
                            String status,
                            Double confidence,
                            List<String> sourceRefs) {

    public ProposedClaim {
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}

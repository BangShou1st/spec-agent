package com.specagent.agent.protocol;

import java.util.List;

/**
 * 文件名:UsageView.java
 *
 * 用途:Brain 返回的、经过脱敏的模型用量统计(调用次数与
 * prompt 哈希)。
 *
 * 约束:只允许哈希——绝不包含 prompt 原文、provider 载荷或凭证。
 */
public record UsageView(int modelCalls, List<String> promptHashes) {

    public UsageView {
        promptHashes = promptHashes == null ? List.of() : List.copyOf(promptHashes);
    }
}

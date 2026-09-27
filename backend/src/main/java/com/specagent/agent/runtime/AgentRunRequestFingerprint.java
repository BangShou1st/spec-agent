package com.specagent.agent.runtime;

import com.specagent.agent.protocol.AgentEvent;
import com.specagent.common.Hashes;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 文件名:AgentRunRequestFingerprint.java
 *
 * 用途:为客户端发起的 agent-run 创建请求计算稳定的逻辑身份指纹(SHA-256),
 * 配合 project 范围的幂等唯一索引实现"同一逻辑请求只落库一个 run"。
 *
 * 指纹只包含客户端可控的稳定请求字段;刻意排除当前活跃 route、route tip 等
 * 运行时解析状态——幂等重试即使在原 run 已经推进或切换了图状态之后,
 * 也必须重放命中原始 run。
 *
 * 字段顺序固定,每个值带长度前缀,因此 null、空串、空白、UUID 与普通文本
 * 不会产生歧义。run id、时间戳等运行时生成的值有意不参与哈希。
 */
public final class AgentRunRequestFingerprint {

    private AgentRunRequestFingerprint() {
    }

    public static String forClientRequest(UUID projectId,
                                          String operation,
                                          UUID nodeId,
                                          UUID sourceRouteId,
                                          UUID answerId,
                                          UUID selectedOptionId,
                                          String freeText) {
        return forClientRequest(projectId, operation, nodeId, sourceRouteId, answerId,
                selectedOptionId, freeText, null);
    }

    public static String forClientRequest(UUID projectId,
                                          String operation,
                                          UUID nodeId,
                                          UUID sourceRouteId,
                                          UUID answerId,
                                          UUID selectedOptionId,
                                          String freeText,
                                          AgentEvent.PersistenceIntent persistenceIntent) {
        return forClientRequest(projectId, operation, nodeId, sourceRouteId, answerId,
                selectedOptionId,
                selectedOptionId == null ? null : List.of(selectedOptionId),
                freeText, persistenceIntent);
    }

    /**
     * 多选变体:完整的选项集合(保持用户选择的顺序)属于逻辑请求身份;
     * 上面的单 id 重载委托到这里传入单元素列表,单选场景下两个方法
     * 产生相同指纹。
     */
    public static String forClientRequest(UUID projectId,
                                          String operation,
                                          UUID nodeId,
                                          UUID sourceRouteId,
                                          UUID answerId,
                                          UUID selectedOptionId,
                                          List<UUID> selectedOptionIds,
                                          String freeText,
                                          AgentEvent.PersistenceIntent persistenceIntent) {
        String optionsField = selectedOptionIds == null ? null
                : selectedOptionIds.stream().map(UUID::toString).collect(Collectors.joining(","));
        String canonical = String.join("|",
                field("projectId", projectId),
                field("operation", operation),
                field("nodeId", nodeId),
                field("sourceRouteId", sourceRouteId),
                field("answerId", answerId),
                field("selectedOptionId", selectedOptionId),
                field("selectedOptionIds", optionsField),
                field("freeText", freeText),
                field("persistenceIntent", persistenceIntent));
        return Hashes.sha256Hex(canonical);
    }

    /**
     * 为 runtime 创建的续跑子 run 计算稳定逻辑身份:同一个父 run 在同一轮深度
     * 下的"子 run 槽位"。哈希只包含循环身份字段——绝不包含冲突、目标、计划
     * 标志等语义字段。配合 project 范围的幂等唯一索引,父 run 终止回调重复
     * 到达时只会命中已落库的那个子 run,而不会创建第二个。
     */
    public static String forContinuation(UUID projectId,
                                         UUID parentRunId,
                                         int cycleIndex) {
        String canonical = String.join("|",
                field("projectId", projectId),
                field("operation", "CONTINUE"),
                field("parentRunId", parentRunId),
                field("cycleIndex", cycleIndex));
        return Hashes.sha256Hex(canonical);
    }

    private static String field(String name, Object value) {
        if (value == null) {
            return name + ":null";
        }
        String text = value.toString();
        return name + ":" + text.length() + ":" + text;
    }
}

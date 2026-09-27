package com.specagent.agent.runtime;

import com.specagent.agent.protocol.AgentEvent;

import java.util.List;
import java.util.UUID;

/**
 * 文件名:CreateRunRequest.java
 *
 * 用途:{@code POST /api/v1/projects/{projectId}/agent-runs} 的请求体。
 *
 * 原样从 controller 的嵌套 record 抽出:组件名(即 JSON 字段名)是线上契约,
 * 不能改动。移到应用层的原因是它的消费方是命令服务,而不是 HTTP 边界。
 */
public record CreateRunRequest(String operation,
                               UUID nodeId,
                               UUID sourceRouteId,
                               UUID selectedOptionId,
                               List<UUID> selectedOptionIds,
                               String freeText,
                               UUID answerId,
                               String idempotencyKey,
                               AgentEvent.PersistenceIntent persistenceIntent) {
}

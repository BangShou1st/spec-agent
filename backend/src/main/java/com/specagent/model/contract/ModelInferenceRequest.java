package com.specagent.model.contract;

import java.util.List;
import java.util.UUID;

/**
 * 文件名:ModelInferenceRequest.java
 *
 * 用途:提供商无关的底层推理请求。携带的是运行时已批准的模型消息,而非
 * {@code AgentTaskType}:提示词构建与编排由调用方(代表 Python 大脑的内部推理
 * 代理)负责。{@code runId} 把每次调用绑定到一个持久化的 agent run;{@code callType}
 * 是记录在 run 事件中的调用类别(已做脱敏)。
 *
 * {@code conversationId} 是调用方能解析到的所属项目。保留服务端会话状态的提供商
 * 按项目维度标识会话,因此同一项目的所有请求呈现同一会话;请求级标识仍按每次调用
 * 区分。该字段可选:不知道所属项目的调用方(兼容性探测、无项目的助手轮次)将其留为
 * {@code null},此时会话回退到 run 维度。
 */
public record ModelInferenceRequest(UUID runId,
                                    String callType,
                                    List<ModelInferenceMessage> messages,
                                    Integer maxOutputTokens,
                                    ModelOutputContract outputContract,
                                    UUID conversationId) {

    public ModelInferenceRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        outputContract = outputContract == null ? ModelOutputContract.text() : outputContract;
    }

    /**
     * 历史构造形态:等价于不带项目亲和性的 {@link ModelOutputContract.Text}。
     * 现有调用方保持原有行为不变。
     */
    public ModelInferenceRequest(UUID runId,
                                 String callType,
                                 List<ModelInferenceMessage> messages,
                                 Integer maxOutputTokens) {
        this(runId, callType, messages, maxOutputTokens, ModelOutputContract.text(), null);
    }

    /**
     * 历史构造形态:不带项目亲和性,会话回退到 run 维度。
     */
    public ModelInferenceRequest(UUID runId,
                                 String callType,
                                 List<ModelInferenceMessage> messages,
                                 Integer maxOutputTokens,
                                 ModelOutputContract outputContract) {
        this(runId, callType, messages, maxOutputTokens, outputContract, null);
    }

    /**
     * 提供商应视为同一会话的标识:已知所属项目时用项目 ID,否则用 run ID。
     */
    public UUID conversationOrRun() {
        return conversationId == null ? runId : conversationId;
    }
}

package com.specagent.assistant.model;

import com.specagent.assistant.model.GlobalAssistantContext;
import com.specagent.model.contract.ModelInferenceGateway;
import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.contract.ModelInferenceResponse;
import com.specagent.model.contract.ModelOutputContract;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 文件名:GlobalAssistantBrain.java
 *
 * 用途:全局助手专属的模型推理入口,是"决策大脑"——把渲染好的上下文交给
 * 模型、把模型输出解析并校验成 {@link GlobalAssistantDecision}。
 * 供应商中立:只依赖 {@code ModelInferenceGateway} 这个接缝,不碰 OpenCode
 * 配置/客户端、不挂供应商适配器、没有第二个客户端、不做重试/降级、不做函数调用。
 *
 * V2 生产路径对所有模型、所有场景使用唯一固定的输出模式:没有按模型分支、
 * 没有按场景切换,也没有语义重试时偷偷换模式。
 */
@Component
public class GlobalAssistantBrain {
    public static final String DECISION_CALL_TYPE = "GLOBAL_ASSISTANT_DECISION";
    public static final String DECISION_REPAIR_CALL_TYPE = "GLOBAL_ASSISTANT_DECISION_REPAIR";
    public static final String SUMMARY_CALL_TYPE = "GLOBAL_ASSISTANT_SUMMARY";
    private final GlobalAssistantPromptRenderer renderer;
    private final ModelInferenceGateway gateway;
    private final GlobalAssistantDecisionParser parser;
    private final GlobalAssistantDecisionValidator validator;
    public GlobalAssistantBrain(GlobalAssistantPromptRenderer renderer, ModelInferenceGateway gateway,
            GlobalAssistantDecisionParser parser, GlobalAssistantDecisionValidator validator) {
        this.renderer = renderer;
        this.gateway = gateway;
        this.parser = parser;
        this.validator = validator;
    }
    /**
     * 冻结的 V1 生产输出模式:JSON_OBJECT。
     * 对所有模型、所有场景都是同一份固定契约。没有按模型分支、
     * 没有按场景切换,也没有静默降级。
     */
    public static ModelOutputContract productionContract() {
        return ModelOutputContract.jsonObject();
    }
    public GlobalAssistantDecision decide(UUID runId, GlobalAssistantContext context,
            List<Map<String, Object>> observations) {
        return decide(runId, context, observations, productionContract());
    }
    /** 带显式契约的资格验证/测试路径。不做静默降级。 */
    public GlobalAssistantDecision decide(UUID runId, GlobalAssistantContext context,
            List<Map<String, Object>> observations, ModelOutputContract contract) {
        return completeDecision(runId, DECISION_CALL_TYPE,
                renderer.render(context, observations), contract);
    }
    /**
     * 对被拒绝的语义决策做且仅做一次结构性修复推理。
     * 上下文、观察列表与生产输出契约完全不变,只附加一段简短且有界的
     * 拒绝原因。内部绝不循环。
     */
    public GlobalAssistantDecision repairDecision(UUID runId, GlobalAssistantContext context,
            List<Map<String, Object>> observations, String rejectionReason) {
        return completeDecision(runId, DECISION_REPAIR_CALL_TYPE,
                renderer.renderRepair(context, observations, rejectionReason), productionContract());
    }
    private GlobalAssistantDecision completeDecision(UUID runId, String callType,
            List<com.specagent.model.contract.ModelInferenceMessage> messages,
            ModelOutputContract contract) {
        ModelInferenceResponse response;
        try {
            response = gateway.complete(new ModelInferenceRequest(runId, callType, messages, 1024,
                    contract));
        } catch (com.specagent.model.contract.StreamCancelledException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new GlobalAssistantModelException("MODEL_UNAVAILABLE", "Model gateway unavailable", ex);
        }
        if (response == null || response.content() == null || response.content().isBlank()) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "Empty model completion");
        }
        return parseAndValidate(response.content());
    }

    private GlobalAssistantDecision parseAndValidate(String content) {
        GlobalAssistantDecision decision = parser.parse(content);
        validator.validate(decision);
        return decision;
    }

    /**
     * {@link #decide(UUID, GlobalAssistantContext, List)} 的流式变体。
     * 每个真实模型片段都会先询问 {@code shouldContinue},因此即使片段里没有
     * 可释放的正文(例如 TOOL JSON),取消也能被轮询到。只有按契约 kind
     * 可释放的助手正文会进入 {@code fragmentSink},控制 JSON 绝不外漏。
     * 返回的决策仍走与阻塞变体相同的严格解析+校验路径。
     */
    public GlobalAssistantDecision decideStreaming(UUID runId, GlobalAssistantContext context,
            List<Map<String, Object>> observations, java.util.function.BooleanSupplier shouldContinue,
            com.specagent.model.contract.FragmentListener fragmentSink) {
        return completeDecisionStreaming(runId, DECISION_CALL_TYPE,
                renderer.render(context, observations), productionContract(), shouldContinue, fragmentSink);
    }

    /** {@link #repairDecision(UUID, GlobalAssistantContext, List, String)} 的流式变体。 */
    public GlobalAssistantDecision repairDecisionStreaming(UUID runId, GlobalAssistantContext context,
            List<Map<String, Object>> observations, String rejectionReason, java.util.function.BooleanSupplier shouldContinue,
            com.specagent.model.contract.FragmentListener fragmentSink) {
        return completeDecisionStreaming(runId, DECISION_REPAIR_CALL_TYPE,
                renderer.renderRepair(context, observations, rejectionReason), productionContract(),
                shouldContinue, fragmentSink);
    }

    private GlobalAssistantDecision completeDecisionStreaming(UUID runId, String callType,
            List<com.specagent.model.contract.ModelInferenceMessage> messages,
            ModelOutputContract contract, java.util.function.BooleanSupplier shouldContinue,
            com.specagent.model.contract.FragmentListener fragmentSink) {
        AssistantTextStreamDecoder decoder = new AssistantTextStreamDecoder();
        ModelInferenceResponse response;
        try {
            response = gateway.completeStreaming(new ModelInferenceRequest(runId, callType, messages,
                    1024, contract), fragment -> {
                // 流控优先:每个模型片段都是一次取消检查点,
                // 即使片段里没有可释放的正文。展示层的放行在下面单独一步进行。
                if (!shouldContinue.getAsBoolean()) {
                    return false;
                }
                String releasable = decoder.append(fragment).releasableText();
                if (!releasable.isEmpty() && !fragmentSink.onFragment(releasable)) {
                    return false;
                }
                return true;
            });
        } catch (com.specagent.model.contract.StreamCancelledException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new GlobalAssistantModelException("MODEL_UNAVAILABLE", "Model gateway unavailable", ex);
        }
        if (response == null || response.content() == null || response.content().isBlank()) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "Empty model completion");
        }
        return parseAndValidate(response.content());
    }
    public String summarize(UUID runId, String recentText) {
        ModelInferenceResponse response;
        try {
            response = gateway.complete(new ModelInferenceRequest(runId, SUMMARY_CALL_TYPE,
                    renderer.renderSummary(recentText), 512,
                    com.specagent.model.contract.ModelOutputContract.text()));
        } catch (RuntimeException ex) {
            throw new GlobalAssistantModelException("MODEL_UNAVAILABLE", "Summary model call failed", ex);
        }
        if (response == null || response.content() == null || response.content().isBlank()) {
            return null;
        }
        String summary = response.content().trim();
        return summary.length() <= 2000 ? summary : summary.substring(0, 2000);
    }
}

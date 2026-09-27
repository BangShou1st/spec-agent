package com.specagent.assistant.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.ModelInferenceGateway;
import com.specagent.model.contract.ModelInferenceMessage;
import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.contract.ModelInferenceResponse;
import com.specagent.model.contract.ModelOutputContract;
import com.specagent.testing.FakeModelInferenceGateway;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 文件名:GlobalAssistantBrainContractTest.java
 *
 * 测试目标:验证调用方的契约装配——决策请求使用结构化输出契约,
 * 摘要保持纯文本契约;同时证明第二个供应商适配器可以在完全不引入
 * 供应商类型的前提下,遵守同一个中立接缝。
 */
class GlobalAssistantBrainContractTest {

    private GlobalAssistantBrain brainWith(
            AtomicReference<ModelInferenceRequest> captured, String scriptedContent) {
        GlobalAssistantPromptRenderer renderer = mock(GlobalAssistantPromptRenderer.class);
        when(renderer.render(any(), any())).thenReturn(List.of(
                new ModelInferenceMessage("system", "system contract"),
                new ModelInferenceMessage("user", "user context")));
        when(renderer.renderSummary(any())).thenReturn(
                List.of(new ModelInferenceMessage("user", "recent text")));
        ModelInferenceGateway stub = request -> {
            captured.set(request);
            return new ModelInferenceResponse(scriptedContent, "stop", 0, 0);
        };
        return new GlobalAssistantBrain(renderer, stub,
                new GlobalAssistantDecisionParser(new ObjectMapper()),
                new GlobalAssistantDecisionValidator());
    }

    @Test
    void decisionRequestsProductionObjectContract() {
        AtomicReference<ModelInferenceRequest> captured = new AtomicReference<>();
        GlobalAssistantBrain brain = brainWith(captured,
                 "{\"assistantText\":\"Hi.\",\"kind\":\"FINAL\"}");

        brain.decide(UUID.randomUUID(), null, List.of());

        assertThat(captured.get().callType()).isEqualTo(GlobalAssistantBrain.DECISION_CALL_TYPE);
        assertThat(captured.get().outputContract())
                .isInstanceOf(ModelOutputContract.JsonObject.class);
    }

    @Test
    void summaryRequestsTextContract() {
        AtomicReference<ModelInferenceRequest> captured = new AtomicReference<>();
        GlobalAssistantBrain brain = brainWith(captured, "  kept summary  ");

        String summary = brain.summarize(UUID.randomUUID(), "recent text");

        assertThat(summary).isEqualTo("kept summary");
        assertThat(captured.get().callType()).isEqualTo(GlobalAssistantBrain.SUMMARY_CALL_TYPE);
        assertThat(captured.get().outputContract()).isInstanceOf(ModelOutputContract.Text.class);
    }

    @Test
    void secondAdapterHonorsSameNeutralContract() {
        FakeModelInferenceGateway fake = new FakeModelInferenceGateway();
        ModelInferenceRequest request = new ModelInferenceRequest(UUID.randomUUID(),
                GlobalAssistantBrain.DECISION_CALL_TYPE,
                List.of(new ModelInferenceMessage("user", "hi")), 64,
                GlobalAssistantDecisionSchema.contract());

        ModelInferenceResponse response = fake.complete(request);

        assertThat(response.content()).isNotBlank();
    }

    @Test
    void productionUsesSingleFixedMode() {
        assertThat(GlobalAssistantBrain.productionContract())
                .isInstanceOf(ModelOutputContract.JsonObject.class);
    }

    @Test
    void explicitContractIsHonoredWithoutFallback() {
        AtomicReference<ModelInferenceRequest> captured = new AtomicReference<>();
        GlobalAssistantBrain brain = brainWith(captured,
                "{\"kind\":\"FINAL\",\"assistantText\":\"Hi.\"}");
        brain.decide(UUID.randomUUID(), null, List.of(), ModelOutputContract.jsonObject());
        assertThat(captured.get().outputContract()).isInstanceOf(ModelOutputContract.JsonObject.class);
        AtomicReference<ModelInferenceRequest> captured2 = new AtomicReference<>();
        GlobalAssistantBrain brain2 = brainWith(captured2,
                "{\"kind\":\"FINAL\",\"assistantText\":\"Hi.\"}");
        brain2.decide(UUID.randomUUID(), null, List.of(), GlobalAssistantDecisionSchema.contract());
        assertThat(captured2.get().outputContract()).isInstanceOf(ModelOutputContract.JsonSchema.class);
    }
}

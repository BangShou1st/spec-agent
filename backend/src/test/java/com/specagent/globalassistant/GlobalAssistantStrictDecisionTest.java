package com.specagent.globalassistant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.globalassistant.model.GlobalAssistantDecisionParser;
import com.specagent.globalassistant.model.GlobalAssistantDecisionValidator;
import com.specagent.globalassistant.model.GlobalAssistantModelException;
import com.specagent.globalassistant.model.GlobalAssistantPromptRenderer;
import com.specagent.model.inference.ModelInferenceGateway;
import com.specagent.model.inference.ModelInferenceResponse;
import com.specagent.globalassistant.model.GlobalAssistantBrain;
import com.specagent.globalassistant.context.GlobalAssistantContextBuilder;
import com.specagent.globalassistant.conversation.GlobalAssistantConversationService;
import com.specagent.globalassistant.context.GlobalAssistantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * FIX A+B RED: strict JSON + decision invariants (review repair).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GlobalAssistantStrictDecisionTest {
    @Autowired GlobalAssistantDecisionParser parser;
    @Autowired GlobalAssistantDecisionValidator validator;
    @Autowired GlobalAssistantPromptRenderer renderer;
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired GlobalAssistantContextBuilder contextBuilder;
    @Test
    void prosePrefixMustFail() {
        assertThatThrownBy(() -> parser.parse("Here is the JSON:\n{\"done\":true, \"assistantText\":\"hi\"}"))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void codeFenceMustFail() {
        assertThatThrownBy(() -> parser.parse("```json\n{\"done\":true, \"assistantText\":\"hi\"}\n```"))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void trailingTextMustFail() {
        assertThatThrownBy(() -> parser.parse("{\"done\":true, \"assistantText\":\"hi\"}\nextra"))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void trailingSecondObjectMustFail() {
        assertThatThrownBy(() -> parser.parse("{\"done\":true, \"assistantText\":\"hi\"} {\"done\":true}"))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void unknownTopLevelFieldMustFail() {
        assertThatThrownBy(() -> parser.parse("{\"done\":true, \"assistantText\":\"hi\", \"extra\":1}"))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void doneIsRequiredBoolean() {
        assertThatThrownBy(() -> parser.parse("{\"assistantText\":\"hi\"}"))
                .isInstanceOf(GlobalAssistantModelException.class);
        assertThatThrownBy(() -> parser.parse("{\"done\":\"true\", \"assistantText\":\"hi\"}"))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void toolPlusDoneMustFailValidation() {
        var decision = parser.parse(
                "{\"done\":true, \"toolRequest\":{\"capabilityId\":\"project.list_recent\", \"arguments\":{}}}");
        assertThatThrownBy(() -> validator.validate(decision))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void toolPlusUiActionMustFail() {
        var decision = parser.parse(
                "{\"done\":false, \"toolRequest\":{\"capabilityId\":\"project.list_recent\", \"arguments\":{}}, \"uiAction\":{\"destination\":\"PROJECTS\"}}");
        assertThatThrownBy(() -> validator.validate(decision))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void projectDestinationRequiresResourceId() {
        var decision = parser.parse(
                "{\"done\":true, \"assistantText\":\"go\", \"uiAction\":{\"destination\":\"PROJECT\"}}");
        assertThatThrownBy(() -> validator.validate(decision))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void nonProjectDestinationMustNotCarryResourceId() {
        var decision = parser.parse("{\"done\":true, \"assistantText\":\"go\", \"uiAction\":{\"destination\":\"PROJECTS\", \"resourceId\":\"00000000-0000-0000-0000-000000000001\"}}");
        assertThatThrownBy(() -> validator.validate(decision))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void finalAnswerRequiresText() {
        var decision = parser.parse("{\"done\":true}");
        assertThatThrownBy(() -> validator.validate(decision))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
}

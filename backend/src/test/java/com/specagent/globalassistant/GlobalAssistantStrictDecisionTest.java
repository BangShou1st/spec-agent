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
         assertThatThrownBy(() -> parser.parse("Here is the JSON:\n{\"kind\":\"FINAL\", \"assistantText\":\"hi\"}"))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void codeFenceMustFail() {
         assertThatThrownBy(() -> parser.parse("```json\n{\"kind\":\"FINAL\", \"assistantText\":\"hi\"}\n```"))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void trailingTextMustFail() {
         assertThatThrownBy(() -> parser.parse("{\"kind\":\"FINAL\", \"assistantText\":\"hi\"}\nextra"))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void trailingSecondObjectMustFail() {
         assertThatThrownBy(() -> parser.parse("{\"kind\":\"FINAL\", \"assistantText\":\"hi\"} {\"done\":true}"))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void unknownTopLevelFieldMustFail() {
         assertThatThrownBy(() -> parser.parse("{\"kind\":\"FINAL\", \"assistantText\":\"hi\", \"extra\":1}"))
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
    void toolPlusTextMustFail() {
        assertThatThrownBy(() -> {
            var decision = parser.parse(
                    "{\"kind\":\"TOOL\",\"assistantText\":\"extra\", \"toolRequest\":{\"capabilityId\":\"project.list_recent\", \"arguments\":{}}}");
            validator.validate(decision);
        }).isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void toolPlusUiActionMustFail() {
        assertThatThrownBy(() -> {
            var decision = parser.parse(
                    "{\"kind\":\"TOOL\", \"toolRequest\":{\"capabilityId\":\"project.list_recent\", \"arguments\":{}}, \"uiAction\":{\"destination\":\"PROJECTS\"}}");
            validator.validate(decision);
        }).isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void projectDestinationRequiresResourceId() {
        var decision = parser.parse(
                 "{\"kind\":\"NAVIGATE\", \"assistantText\":\"go\", \"uiAction\":{\"destination\":\"PROJECT\"}}");
        assertThatThrownBy(() -> validator.validate(decision))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void nonProjectDestinationMustNotCarryResourceId() {
         var decision = parser.parse("{\"kind\":\"NAVIGATE\", \"assistantText\":\"go\", \"uiAction\":{\"destination\":\"PROJECTS\", \"resourceId\":\"00000000-0000-0000-0000-000000000001\"}}");
        assertThatThrownBy(() -> validator.validate(decision))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void finalAnswerRequiresText() {
        assertThatThrownBy(() -> {
            var decision = parser.parse("{\"kind\":\"FINAL\"}");
            validator.validate(decision);
        }).isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void createRejectsUnknownArgument() {
        var decision = parser.parse(
                 "{\"kind\":\"TOOL\", \"toolRequest\":{\"capabilityId\":\"project.create\", \"arguments\":{\"title\":\"T\", \"mode\":\"fast\"}}}");
        assertThatThrownBy(() -> validator.validate(decision))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void searchRejectsUnknownArgument() {
        var decision = parser.parse(
                 "{\"kind\":\"TOOL\", \"toolRequest\":{\"capabilityId\":\"project.search\", \"arguments\":{\"query\":\"x\", \"sort\":\"recent\"}}}");
        assertThatThrownBy(() -> validator.validate(decision))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void listRecentRejectsUnknownArgument() {
        var decision = parser.parse(
                 "{\"kind\":\"TOOL\", \"toolRequest\":{\"capabilityId\":\"project.list_recent\", \"arguments\":{\"limit\":5, \"offset\":2}}}");
        assertThatThrownBy(() -> validator.validate(decision))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void getSummaryRejectsUnknownArgument() {
        var decision = parser.parse(
                 "{\"kind\":\"TOOL\", \"toolRequest\":{\"capabilityId\":\"project.get_summary\", \"arguments\":{\"projectId\":\"00000000-0000-0000-0000-000000000001\", \"verbose\":true}}}");
        assertThatThrownBy(() -> validator.validate(decision))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void fractionalLimitIsRejected() {
        var decision = parser.parse(
                 "{\"kind\":\"TOOL\", \"toolRequest\":{\"capabilityId\":\"project.search\", \"arguments\":{\"query\":\"x\", \"limit\":1.5}}}");
        assertThatThrownBy(() -> validator.validate(decision))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void integerLimitIsAccepted() {
        var decision = parser.parse(
                 "{\"kind\":\"TOOL\", \"toolRequest\":{\"capabilityId\":\"project.search\", \"arguments\":{\"query\":\"x\", \"limit\":5}}}");
        validator.validate(decision);
    }
}

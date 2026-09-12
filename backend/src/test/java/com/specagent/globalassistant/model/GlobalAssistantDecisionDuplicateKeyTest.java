package com.specagent.globalassistant.model;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
/** Authoritative parser fails closed on any duplicate JSON object member. */
class GlobalAssistantDecisionDuplicateKeyTest {
    private final GlobalAssistantDecisionParser parser =
            new GlobalAssistantDecisionParser(new ObjectMapper());
    private void rejects(String json) {
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test void duplicateKind() {
        rejects("{\"kind\":\"FINAL\",\"assistantText\":\"a\",\"kind\":\"TOOL\",\"toolRequest\":{\"capabilityId\":\"project.search\",\"arguments\":{\"query\":\"x\"}}}");
    }
    @Test void duplicateAssistantText() {
        rejects("{\"kind\":\"FINAL\",\"assistantText\":\"wrong\",\"assistantText\":\"right\"}");
    }
    @Test void duplicateToolRequest() {
        rejects("{\"kind\":\"TOOL\",\"toolRequest\":{\"capabilityId\":\"project.search\",\"arguments\":{\"query\":\"x\"}},\"toolRequest\":{\"capabilityId\":\"project.search\",\"arguments\":{\"query\":\"y\"}}}");
    }
    @Test void duplicateUiAction() {
        rejects("{\"kind\":\"NAVIGATE\",\"assistantText\":\"go\",\"uiAction\":{\"destination\":\"PROJECTS\"},\"uiAction\":{\"destination\":\"SKILLS\"}}");
    }
    @Test void nestedDuplicateCapabilityId() {
        rejects("{\"kind\":\"TOOL\",\"toolRequest\":{\"capabilityId\":\"project.search\",\"capabilityId\":\"project.list_recent\",\"arguments\":{}}}");
    }
    @Test void nestedDuplicateArguments() {
        rejects("{\"kind\":\"TOOL\",\"toolRequest\":{\"capabilityId\":\"project.search\",\"arguments\":{\"query\":\"x\"},\"arguments\":{\"query\":\"y\"}}}");
    }
    @Test void nestedDuplicateDestination() {
        rejects("{\"kind\":\"NAVIGATE\",\"uiAction\":{\"destination\":\"PROJECTS\",\"destination\":\"SKILLS\"}}");
    }
    @Test void nestedDuplicateResourceId() {
        rejects("{\"kind\":\"NAVIGATE\",\"uiAction\":{\"destination\":\"PROJECT\",\"resourceId\":\"11111111-1111-4111-8111-111111111111\",\"resourceId\":\"22222222-2222-4222-8222-222222222222\"}}");
    }
    @Test void validDecisionsStillParse() {
        assertThat(parser.parse("{\"kind\":\"FINAL\",\"assistantText\":\"ok\"}").kind())
                .isEqualTo(GlobalAssistantDecision.DecisionKind.FINAL);
        assertThat(parser.parse("{\"kind\":\"TOOL\",\"toolRequest\":{\"capabilityId\":\"project.search\",\"arguments\":{\"query\":\"x\"}}}").kind())
                .isEqualTo(GlobalAssistantDecision.DecisionKind.TOOL);
    }
}

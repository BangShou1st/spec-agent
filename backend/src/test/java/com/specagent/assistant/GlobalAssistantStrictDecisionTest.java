package com.specagent.assistant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.assistant.model.GlobalAssistantDecisionParser;
import com.specagent.assistant.model.GlobalAssistantDecisionValidator;
import com.specagent.assistant.model.GlobalAssistantModelException;
import com.specagent.assistant.model.GlobalAssistantPromptRenderer;
import com.specagent.model.contract.ModelInferenceGateway;
import com.specagent.model.contract.ModelInferenceResponse;
import com.specagent.assistant.model.GlobalAssistantBrain;
import com.specagent.assistant.runtime.GlobalAssistantContextBuilder;
import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.assistant.model.GlobalAssistantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文件名:GlobalAssistantStrictDecisionTest.java
 *
 * 测试目标:验证决策输出的严格 JSON 与决策不变量(供修复机制审查)。
 * 覆盖场景:散文前缀、代码围栏、尾部多余文本/对象、未知顶层字段、
 * 缺失或类型错误的 done 字段都会被拒绝;工具决策不得夹带
 * assistantText 或 uiAction;PROJECT 导航必须携带 resourceId 而
 * 其他目的地不得携带;FINAL 必须有文本;各工具拒绝未知参数,
 * limit 必须是整数。
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

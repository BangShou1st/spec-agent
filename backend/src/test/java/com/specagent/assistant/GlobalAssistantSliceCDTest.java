package com.specagent.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.assistant.model.GlobalAssistantContext;
import com.specagent.assistant.runtime.GlobalAssistantContextBuilder;
import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.assistant.conversation.GlobalAssistantThread;
import com.specagent.assistant.model.GlobalAssistantBrain;
import com.specagent.assistant.model.GlobalAssistantDecision;
import com.specagent.assistant.model.GlobalAssistantDecisionParser;
import com.specagent.assistant.model.GlobalAssistantDecisionValidator;
import com.specagent.assistant.model.GlobalAssistantModelException;
import com.specagent.assistant.model.GlobalAssistantPromptRenderer;
import com.specagent.model.contract.ModelInferenceGateway;
import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.contract.ModelInferenceResponse;
import com.specagent.workspace.project.ProjectService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文件名:GlobalAssistantSliceCDTest.java
 *
 * 测试目标:Slice C+D——有界的上下文构建、严格解析器、失败收场的校验器,
 * 以及供应商中立的大脑(网关打桩,不与 OpenCode 耦合)。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GlobalAssistantSliceCDTest {
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired GlobalAssistantContextBuilder contextBuilder;
    @Autowired GlobalAssistantDecisionParser parser;
    @Autowired GlobalAssistantDecisionValidator validator;
    @Autowired GlobalAssistantPromptRenderer renderer;
    @Autowired ProjectService projects;
    @Autowired ObjectMapper mapper;
    @Test
    void contextIsBounded() {
        GlobalAssistantThread thread = conversations.createThread();
        for (int i = 0; i < 40; i++) {
            conversations.appendUserMessage(thread.id(), "message number " + i + " with padding ".repeat(20), null);
        }
        GlobalAssistantContext context = contextBuilder.build(thread.id(), "current",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        // 尚无摘要:所有未摘要消息都在硬上限内保留。
        assertThat(context.recentConversation()).hasSizeLessThanOrEqualTo(33);
        // V1 的六个工具:四个项目工具,加上只读的 skill 发现与只做暂存的 skill 导入。
        assertThat(context.toolDescriptors()).hasSize(6);
        assertThat(context.recentProjectHints()).hasSizeLessThanOrEqualTo(5);
        for (GlobalAssistantContext.ConversationTurn turn : context.recentConversation()) {
            assertThat(turn.content().length()).isLessThanOrEqualTo(2001);
        }
    }
    @Test
    void parserAcceptsMinimalFinalDecision() {
        GlobalAssistantDecision decision =
                parser.parse("{\"kind\":\"FINAL\",\"assistantText\": \"Done.\"}");
        assertThat(decision.kind()).isEqualTo(GlobalAssistantDecision.DecisionKind.FINAL);
        assertThat(decision.toolRequest()).isNull();
    }
    @Test
    void parserRejectsMalformedJson() {
        assertThatThrownBy(() -> parser.parse("not json at all"))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void validatorRejectsUnknownCapability() {
        GlobalAssistantDecision decision = new GlobalAssistantDecision(
                GlobalAssistantDecision.DecisionKind.TOOL, null,
                new GlobalAssistantDecision.ToolRequest("skill.secret", Map.of()),
                null);
        assertThatThrownBy(() -> validator.validate(decision))
                .isInstanceOf(GlobalAssistantModelException.class)
                .hasMessageContaining("not in Global Assistant catalog");
    }
    @Test
    void validatorRejectsForgedProjectId() {
        GlobalAssistantDecision decision = new GlobalAssistantDecision(
                GlobalAssistantDecision.DecisionKind.TOOL, null,
                new GlobalAssistantDecision.ToolRequest(
                        "project.get_summary", Map.of("projectId", "not-a-uuid")),
                null);
        assertThatThrownBy(() -> validator.validate(decision))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void validatorRejectsArbitraryUrlUiAction() {
        GlobalAssistantDecision decision = new GlobalAssistantDecision(
                GlobalAssistantDecision.DecisionKind.NAVIGATE, "go", null,
                new GlobalAssistantDecision.UiAction(
                        GlobalAssistantDecision.UiDestination.PROJECT, "https://evil.example/x"));
        assertThatThrownBy(() -> validator.validate(decision))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void validatorRejectsEmptyClarification() {
        GlobalAssistantDecision decision = new GlobalAssistantDecision(
                GlobalAssistantDecision.DecisionKind.CLARIFY, "   ", null, null);
        assertThatThrownBy(() -> validator.validate(decision))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void validatorAcceptsSkillImportDecision() {
        // V1 文档化契约:助手可以暂存一条 Skill 导入。
        // 这里是对一次线上事故的回归守护:当时目录已声明 skill.import,
        // 但决策校验器把它当未知工具拒绝,导致每次安装都以
        // MODEL_INVALID_RESPONSE 收场。
        GlobalAssistantDecision decision = new GlobalAssistantDecision(
                GlobalAssistantDecision.DecisionKind.TOOL, null,
                new GlobalAssistantDecision.ToolRequest("skill.import",
                        Map.of("url", "https://github.com/obra/superpowers",
                                "skill", "skills/brainstorming")),
                null);
        validator.validate(decision);
    }
    @Test
    void validatorRejectsSkillImportWithoutUrl() {
        GlobalAssistantDecision decision = new GlobalAssistantDecision(
                GlobalAssistantDecision.DecisionKind.TOOL, null,
                new GlobalAssistantDecision.ToolRequest("skill.import",
                        Map.of("skill", "skills/brainstorming")),
                null);
        assertThatThrownBy(() -> validator.validate(decision))
                .isInstanceOf(GlobalAssistantModelException.class)
                .hasMessageContaining("url");
    }
    @Test
    void validatorRejectsSkillImportUnknownArgument() {
        GlobalAssistantDecision decision = new GlobalAssistantDecision(
                GlobalAssistantDecision.DecisionKind.TOOL, null,
                new GlobalAssistantDecision.ToolRequest("skill.import",
                        Map.of("url", "https://github.com/obra/superpowers",
                                "path", "skills/brainstorming")),
                null);
        assertThatThrownBy(() -> validator.validate(decision))
                .isInstanceOf(GlobalAssistantModelException.class)
                .hasMessageContaining("Unknown argument");
    }
    @Test
    void promptOffersTheSkillImportToolToTheModel() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantContext context = contextBuilder.build(thread.id(), "install a skill",
                new GlobalAssistantContextBuilder.UiRequest("SKILLS", null));
        String tools = renderer.render(context, List.of()).get(1).content();

        // 新工具必须真正到达模型,包括参数和副作用类别,否则助手依然无法发起导入。
        assertThat(tools).contains("skill.import");
        assertThat(tools).contains("\"url\"");
        assertThat(tools).contains("\"ref\"");
        assertThat(tools).contains("\"skill\"");
        assertThat(tools).contains("sideEffectClass=LOCAL_DURABLE");
        assertThat(tools).contains("stagedImportId");
    }

    @Test
    void promptHasNoBenchmarkPhraseRouting() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantContext context = contextBuilder.build(thread.id(), "hello",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        String rendered = renderer.render(context, List.of()).get(0).content();
        assertThat(rendered).doesNotContain("\u6253\u5f00");
        assertThat(rendered).doesNotContain("\u652f\u4ed8");
        assertThat(rendered).doesNotContain("\u90ae\u4ef6");
        assertThat(rendered).contains("Do not invent project IDs");
        assertThat(rendered).contains("canonical");
        assertThat(rendered).contains("TOOL");
        assertThat(rendered).contains("CLARIFY");
        assertThat(rendered).contains("NAVIGATE");
        assertThat(rendered).contains("FINAL");
    }
    @Test
    void brainUsesOnlyGatewayAndValidates() {
        ModelInferenceGateway stub = request -> new ModelInferenceResponse(
                "{\"toolRequest\": {\"capabilityId\": \"project.list_recent\", \"arguments\": {}}, "
                         + "\"kind\":\"TOOL\"}",
                "stop", 0, 0);
        GlobalAssistantBrain brain = new GlobalAssistantBrain(
                renderer, stub, parser, validator);
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantContext context = contextBuilder.build(thread.id(), "show recents",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        GlobalAssistantDecision decision =
                brain.decide(UUID.randomUUID(), context, List.of());
        assertThat(decision.toolRequest().capabilityId()).isEqualTo("project.list_recent");
    }
    @Test
    void brainMapsGatewayFailureToModelUnavailable() {
        ModelInferenceGateway failing = request -> {
            throw new IllegalStateException("boom");
        };
        GlobalAssistantBrain brain = new GlobalAssistantBrain(
                renderer, failing, parser, validator);
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantContext context = contextBuilder.build(thread.id(), "hi",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThatThrownBy(() -> brain.decide(UUID.randomUUID(), context, List.of()))
                .isInstanceOf(GlobalAssistantModelException.class)
                .matches(ex -> ((GlobalAssistantModelException) ex).errorCode().equals("MODEL_UNAVAILABLE"));
    }
}

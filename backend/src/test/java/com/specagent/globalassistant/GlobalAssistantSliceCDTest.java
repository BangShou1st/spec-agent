package com.specagent.globalassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.globalassistant.context.GlobalAssistantContext;
import com.specagent.globalassistant.context.GlobalAssistantContextBuilder;
import com.specagent.globalassistant.conversation.GlobalAssistantConversationService;
import com.specagent.globalassistant.conversation.GlobalAssistantThread;
import com.specagent.globalassistant.model.GlobalAssistantBrain;
import com.specagent.globalassistant.model.GlobalAssistantDecision;
import com.specagent.globalassistant.model.GlobalAssistantDecisionParser;
import com.specagent.globalassistant.model.GlobalAssistantDecisionValidator;
import com.specagent.globalassistant.model.GlobalAssistantModelException;
import com.specagent.globalassistant.model.GlobalAssistantPromptRenderer;
import com.specagent.model.inference.ModelInferenceGateway;
import com.specagent.model.inference.ModelInferenceRequest;
import com.specagent.model.inference.ModelInferenceResponse;
import com.specagent.project.ProjectService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Slices C+D: bounded context, strict parser, fail-closed validator,
 * provider-neutral brain (stubbed gateway, no OpenCode coupling).
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
        // No summary yet: every message is unsummarized remainder inside the hard bound.
        assertThat(context.recentConversation()).hasSizeLessThanOrEqualTo(33);
        assertThat(context.toolDescriptors()).hasSize(4);
        assertThat(context.recentProjectHints()).hasSizeLessThanOrEqualTo(5);
        for (GlobalAssistantContext.ConversationTurn turn : context.recentConversation()) {
            assertThat(turn.content().length()).isLessThanOrEqualTo(2001);
        }
    }
    @Test
    void parserAcceptsMinimalFinalDecision() {
        GlobalAssistantDecision decision =
                parser.parse("{\"assistantText\": \"Done.\", \"done\": true}");
        assertThat(decision.done()).isTrue();
        assertThat(decision.toolRequest()).isNull();
    }
    @Test
    void parserRejectsMalformedJson() {
        assertThatThrownBy(() -> parser.parse("not json at all"))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void validatorRejectsUnknownCapability() {
        GlobalAssistantDecision decision = new GlobalAssistantDecision("hi", null,
                new GlobalAssistantDecision.ToolRequest("skill.secret", Map.of()),
                null, false, false);
        assertThatThrownBy(() -> validator.validate(decision))
                .isInstanceOf(GlobalAssistantModelException.class)
                .hasMessageContaining("not in Global Assistant catalog");
    }
    @Test
    void validatorRejectsForgedProjectId() {
        GlobalAssistantDecision decision = new GlobalAssistantDecision(null, null,
                new GlobalAssistantDecision.ToolRequest(
                        "project.get_summary", Map.of("projectId", "not-a-uuid")),
                null, false, false);
        assertThatThrownBy(() -> validator.validate(decision))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void validatorRejectsArbitraryUrlUiAction() {
        GlobalAssistantDecision decision = new GlobalAssistantDecision("go", null, null,
                new GlobalAssistantDecision.UiAction(
                        GlobalAssistantDecision.UiDestination.PROJECT, "https://evil.example/x"),
                false, true);
        assertThatThrownBy(() -> validator.validate(decision))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test
    void validatorRejectsNonTerminalClarification() {
        GlobalAssistantDecision decision = new GlobalAssistantDecision("which one?", null, null,
                null, true, false);
        assertThatThrownBy(() -> validator.validate(decision))
                .isInstanceOf(GlobalAssistantModelException.class);
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
        assertThat(rendered).contains("Use tools when canonical application state is required");
    }
    @Test
    void brainUsesOnlyGatewayAndValidates() {
        ModelInferenceGateway stub = request -> new ModelInferenceResponse(
                "{\"toolRequest\": {\"capabilityId\": \"project.list_recent\", \"arguments\": {}}, "
                        + "\"done\": false}",
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

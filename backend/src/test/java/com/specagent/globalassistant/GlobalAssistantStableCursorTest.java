package com.specagent.globalassistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.specagent.globalassistant.context.GlobalAssistantContext;
import com.specagent.globalassistant.context.GlobalAssistantContextBuilder;
import com.specagent.globalassistant.conversation.GlobalAssistantConversationService;
import com.specagent.globalassistant.conversation.GlobalAssistantMessage;
import com.specagent.globalassistant.conversation.GlobalAssistantThread;
import com.specagent.globalassistant.model.GlobalAssistantBrain;
import com.specagent.globalassistant.model.GlobalAssistantDecisionParser;
import com.specagent.globalassistant.model.GlobalAssistantDecisionValidator;
import com.specagent.globalassistant.model.GlobalAssistantPromptRenderer;
import com.specagent.globalassistant.model.GlobalAssistantSummaryService;
import com.specagent.model.inference.ModelInferenceGateway;
import com.specagent.model.inference.ModelInferenceRequest;
import com.specagent.model.inference.ModelInferenceResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stable cursor RED: append-monotonic sequence, tie-proof chunks, remainder.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GlobalAssistantStableCursorTest {
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired GlobalAssistantContextBuilder contextBuilder;
    @Autowired GlobalAssistantPromptRenderer renderer;
    @Autowired GlobalAssistantDecisionParser parser;
    @Autowired GlobalAssistantDecisionValidator validator;
    @Autowired JdbcTemplate jdbc;
    private void tieAllTimestamps(UUID threadId) {
        jdbc.update("UPDATE global_assistant_messages SET created_at = TIMESTAMP '2026-01-01 00:00:00' WHERE thread_id = ?",
                threadId);
    }
    private List<String> contents(UUID threadId) {
        return conversations.listMessages(threadId).stream().map(GlobalAssistantMessage::content).toList();
    }
    @Test
    void laterAppendsWithTiedTimestampsKeepCanonicalOrder() {
        GlobalAssistantThread thread = conversations.createThread();
        for (int i = 0; i < 5; i++) {
            conversations.appendUserMessage(thread.id(), "order-" + i, null);
        }
        tieAllTimestamps(thread.id());
        for (int i = 5; i < 8; i++) {
            conversations.appendUserMessage(thread.id(), "order-" + i, null);
        }
        tieAllTimestamps(thread.id());
        assertThat(contents(thread.id())).containsExactly(
                "order-0", "order-1", "order-2", "order-3", "order-4",
                "order-5", "order-6", "order-7");
    }
    @Test
    void summaryChunksStayDisjointAcrossTiedAppends() {
        AtomicInteger summaryCalls = new AtomicInteger();
        List<String> summaryInputs = new ArrayList<>();
        ModelInferenceGateway counting = (ModelInferenceRequest request) -> {
            if ("GLOBAL_ASSISTANT_SUMMARY".equals(request.callType())) {
                summaryCalls.incrementAndGet();
                summaryInputs.add(request.messages().get(request.messages().size() - 1).content());
                return new ModelInferenceResponse("chunk summary " + summaryCalls.get() + ".", "stop", 0, 0);
            }
             return new ModelInferenceResponse("{\"kind\":\"FINAL\", \"assistantText\":\"ok\"}", "stop", 0, 0);
        };
        var summaries = new GlobalAssistantSummaryService(conversations,
                new GlobalAssistantBrain(renderer, counting, parser, validator));
        GlobalAssistantThread thread = conversations.createThread();
        for (int i = 0; i < 34; i++) {
            conversations.appendUserMessage(thread.id(), "tie-" + i, null);
        }
        tieAllTimestamps(thread.id());
        assertThat(summaries.maybeSummarize(thread.id(), UUID.randomUUID())).isTrue();
        List<String> chunk1 = userLines(summaryInputs.get(0));
        assertThat(chunk1).hasSize(10);
        for (int i = 34; i < 44; i++) {
            conversations.appendUserMessage(thread.id(), "tie-" + i, null);
        }
        tieAllTimestamps(thread.id());
        assertThat(summaries.maybeSummarize(thread.id(), UUID.randomUUID())).isTrue();
        List<String> chunk2 = userLines(summaryInputs.get(1));
        assertThat(chunk2).hasSize(10);
        assertThat(java.util.Collections.disjoint(new HashSet<>(chunk1), new HashSet<>(chunk2))).isTrue();
        List<String> canonicalFirst20 =
                contents(thread.id()).subList(0, 20).stream().sorted().toList();
        List<String> union = new ArrayList<>(chunk1);
        union.addAll(chunk2);
        assertThat(union.stream().sorted().toList()).isEqualTo(canonicalFirst20);
    }
    private GlobalAssistantThread threadWithMessages(int count, int summaryVersion) {
        GlobalAssistantThread thread = conversations.createThread();
        for (int i = 0; i < count; i++) {
            conversations.appendUserMessage(thread.id(), "ctx-" + i, null);
        }
        for (int version = 0; version < summaryVersion; version++) {
            conversations.writeSummary(thread.id(), "previous summary", version);
        }
        return thread;
    }
    private List<String> recentContents(UUID threadId) {
        var context = contextBuilder.build(threadId, null, "probe",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        return context.recentConversation().stream()
                .map(GlobalAssistantContext.ConversationTurn::content).toList();
    }
    @Test
    void remainderCaseA34MessagesVersion1Keeps24() {
        GlobalAssistantThread thread = threadWithMessages(34, 1);
        List<String> canonical = contents(thread.id());
        assertThat(recentContents(thread.id())).isEqualTo(canonical.subList(10, 34));
    }
    @Test
    void remainderCaseB35MessagesVersion1RetainsFirstUnsumarized() {
        GlobalAssistantThread thread = threadWithMessages(35, 1);
        List<String> canonical = contents(thread.id());
        List<String> recent = recentContents(thread.id());
        assertThat(recent).isEqualTo(canonical.subList(10, 35));
        assertThat(recent.get(0)).isEqualTo(canonical.get(10));
    }
    @Test
    void remainderCaseC43MessagesVersion1StaysBoundedAt33() {
        GlobalAssistantThread thread = threadWithMessages(43, 1);
        List<String> canonical = contents(thread.id());
        assertThat(recentContents(thread.id())).isEqualTo(canonical.subList(10, 43));
    }
    @Test
    void remainderCaseD44MessagesVersion2Keeps24() {
        GlobalAssistantThread thread = threadWithMessages(44, 2);
        List<String> canonical = contents(thread.id());
        assertThat(recentContents(thread.id())).isEqualTo(canonical.subList(20, 44));
    }
    @Test
    void remainderCaseESummaryLagNeverExceedsHardBound() {
        GlobalAssistantThread thread = threadWithMessages(60, 0);
        assertThat(recentContents(thread.id())).hasSizeLessThanOrEqualTo(33);
    }
    private static List<String> userLines(String summaryInput) {
        List<String> lines = new ArrayList<>();
        for (String line : summaryInput.split("\n")) {
            if (line.startsWith("USER: ")) {
                lines.add(line.substring("USER: ".length()));
            }
        }
        return lines;
    }
}

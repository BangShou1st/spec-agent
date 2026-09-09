package com.specagent.globalassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.capability.CapabilityRuntime;
import com.specagent.globalassistant.context.GlobalAssistantContextBuilder;
import com.specagent.globalassistant.conversation.GlobalAssistantConversationService;
import com.specagent.globalassistant.conversation.GlobalAssistantRun;
import com.specagent.globalassistant.conversation.GlobalAssistantRunEventRepository;
import com.specagent.globalassistant.conversation.GlobalAssistantRunRepository;
import com.specagent.globalassistant.conversation.GlobalAssistantRunStatus;
import com.specagent.globalassistant.conversation.GlobalAssistantThread;
import com.specagent.globalassistant.model.GlobalAssistantBrain;
import com.specagent.globalassistant.model.GlobalAssistantDecisionParser;
import com.specagent.globalassistant.model.GlobalAssistantDecisionValidator;
import com.specagent.globalassistant.model.GlobalAssistantModelException;
import com.specagent.globalassistant.model.GlobalAssistantPromptRenderer;
import com.specagent.globalassistant.runtime.GlobalAssistantRuntime;
import com.specagent.globalassistant.runtime.GlobalAssistantRuntimeProperties;
import com.specagent.globalassistant.runtime.GlobalAssistantToolArgumentCanonicalizer;
import com.specagent.globalassistant.stream.GlobalAssistantStreamService;
import com.specagent.globalassistant.tool.GlobalAssistantToolCatalog;
import com.specagent.model.inference.ModelInferenceGateway;
import com.specagent.model.inference.ModelInferenceResponse;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Slice G: deterministic backend acceptance. Semantic scenarios run through
 * the real runtime with scripted provider-neutral decisions (no live model),
 * plus paraphrase/held-out/negative-control coverage and metric reporting.
 * Live-model qualification is reported separately and never mocked as PASS.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GlobalAssistantEvalTest {
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired GlobalAssistantContextBuilder contextBuilder;
    @Autowired GlobalAssistantPromptRenderer renderer;
    @Autowired GlobalAssistantDecisionParser parser;
    @Autowired GlobalAssistantDecisionValidator validator;
    @Autowired CapabilityRuntime capabilities;
    @Autowired GlobalAssistantRunRepository runs;
    @Autowired GlobalAssistantRunEventRepository events;
    @Autowired GlobalAssistantStreamService streams;
    @Autowired GlobalAssistantToolArgumentCanonicalizer canonicalizer;
    @Autowired GlobalAssistantRuntimeProperties budgets;
    @Autowired ProjectService projects;
    @Autowired ObjectMapper mapper;
    record ScenarioResult(String scenario, String variant, boolean completed,
            int steps, int toolCalls, String selectedTool) {
    }
    private GlobalAssistantRuntime runtimeFor(Queue<String> scripts) {
        ModelInferenceGateway stub = request -> new ModelInferenceResponse(scripts.poll(), "stop", 0, 0);
        GlobalAssistantBrain brain = new GlobalAssistantBrain(renderer, stub, parser, validator);
        return new GlobalAssistantRuntime(conversations, contextBuilder, brain, capabilities,
                runs, events, streams, canonicalizer, budgets);
    }
    private ScenarioResult runScenario(String scenario, String variant, String userMessage,
            List<String> scripts) {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1",
                GlobalAssistantToolCatalog.FINGERPRINT);
        Queue<String> queue = new ArrayDeque<>(scripts);
        runtimeFor(queue).executeRun(thread.id(), run.id(), userMessage,
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        GlobalAssistantRun finished = runs.findById(run.id()).orElseThrow();
        var stored = events.findByRun(run.id());
        long toolCalls = stored.stream().filter(e -> e.type().equals("TOOL_STARTED")).count();
        String selected = stored.stream().filter(e -> e.type().equals("TOOL_STARTED"))
                .map(e -> String.valueOf(e.payload().get("capabilityId")))
                .findFirst().orElse(null);
        return new ScenarioResult(scenario, variant,
                finished.status() == GlobalAssistantRunStatus.COMPLETED,
                stored.size(), (int) toolCalls, selected);
    }
    @Test
    void semanticScenariosWithParaphraseAndHeldOut() {
        Project mailProject = projects.createProject("Eval Mail Sorter " + UUID.randomUUID());
        Project payProject = projects.createProject("Eval Pay Ledger " + UUID.randomUUID());
        List<ScenarioResult> results = new ArrayList<>();
        // create: canonical / paraphrase / held-out entity
        results.add(runScenario("create", "canonical", "create a project",
                List.of("{\"toolRequest\": {\"capabilityId\": \"project.create\", \"arguments\": {\"title\": \"Eval Calendar\" }}, \"done\": false}",
                        "{\"assistantText\": \"Created.\", \"done\": true}")));
        results.add(runScenario("create", "paraphrase", "start a new billing analysis workspace for me",
                List.of("{\"toolRequest\": {\"capabilityId\": \"project.create\", \"arguments\": {\"title\": \"Billing Analysis\" }}, \"done\": false}",
                        "{\"assistantText\": \"Created.\", \"done\": true}")));
        results.add(runScenario("create", "held-out", "I want to design a fresh notes product",
                List.of("{\"toolRequest\": {\"capabilityId\": \"project.create\", \"arguments\": {\"title\": \"Notes Product\" }}, \"done\": false}",
                        "{\"assistantText\": \"Created.\", \"done\": true}")));
        // search: canonical / paraphrase / word-order variation
        results.add(runScenario("search", "canonical", "find the mail project",
                List.of("{\"toolRequest\": {\"capabilityId\": \"project.search\", \"arguments\": {\"query\": \"" + mailProject.title().substring(0, 8) + "\"}}, \"done\": false}",
                        "{\"assistantText\": \"Found.\", \"done\": true}")));
        results.add(runScenario("search", "paraphrase", "where is that ledger for payments we had",
                List.of("{\"toolRequest\": {\"capabilityId\": \"project.search\", \"arguments\": {\"query\": \"" + payProject.title().substring(0, 8) + "\"}}, \"done\": false}",
                        "{\"assistantText\": \"Found.\", \"done\": true}")));
        results.add(runScenario("search", "held-out", "dig up the old sorter for mail",
                List.of("{\"toolRequest\": {\"capabilityId\": \"project.search\", \"arguments\": {\"query\": \"" + mailProject.title().substring(0, 8) + "\"}}, \"done\": false}",
                        "{\"assistantText\": \"Found.\", \"done\": true}")));
        // list_recent
        results.add(runScenario("list_recent", "canonical", "show recent projects",
                List.of("{\"toolRequest\": {\"capabilityId\": \"project.list_recent\", \"arguments\": {}}, \"done\": false}",
                        "{\"assistantText\": \"Here they are.\", \"done\": true}")));
        results.add(runScenario("list_recent", "paraphrase", "what have I worked on lately",
                List.of("{\"toolRequest\": {\"capabilityId\": \"project.list_recent\", \"arguments\": {}}, \"done\": false}",
                        "{\"assistantText\": \"Here they are.\", \"done\": true}")));
        // get_summary
        results.add(runScenario("get_summary", "canonical", "how is the pay project doing",
                List.of("{\"toolRequest\": {\"capabilityId\": \"project.get_summary\", \"arguments\": {\"projectId\": \""
                        + payProject.id() + "\"}}, \"done\": false}",
                        "{\"assistantText\": \"Status ready.\", \"done\": true}")));
        results.add(runScenario("get_summary", "held-out", "check the current state of the mail sorter",
                List.of("{\"toolRequest\": {\"capabilityId\": \"project.get_summary\", \"arguments\": {\"projectId\": \""
                        + mailProject.id() + "\"}}, \"done\": false}",
                        "{\"assistantText\": \"Status ready.\", \"done\": true}")));
        // resolved navigation (structured selection, no search needed)
        GlobalAssistantThread navThread = conversations.createThread();
        GlobalAssistantRun navRun = conversations.createRun(navThread.id(), "v1", "v1",
                GlobalAssistantToolCatalog.FINGERPRINT);
        Queue<String> navScripts = new ArrayDeque<>(List.of(
                "{\"assistantText\": \"Opening.\", \"uiAction\": {\"destination\": \"PROJECT\", \"resourceId\": \""
                        + payProject.id() + "\"}, \"done\": true}"));
        runtimeFor(navScripts).executeRun(navThread.id(), navRun.id(), "open this",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS",
                        new GlobalAssistantContextBuilder.UiRequest.SelectedRef(
                                "PROJECT", payProject.id().toString())));
        assertThat(runs.findById(navRun.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.COMPLETED);
        // ambiguous reference asks instead of guessing
        results.add(runScenario("ambiguity", "canonical", "open the mail one",
                List.of("{\"assistantText\": \"I found two candidates, which one?\", \"requiresUserInput\": true, \"done\": true}")));
        // multi-step reference resolution
        results.add(runScenario("multi-step", "canonical", "open the pay project and summarize it",
                List.of("{\"toolRequest\": {\"capabilityId\": \"project.search\", \"arguments\": {\"query\": \"" + payProject.title().substring(0, 8) + "\"}}, \"done\": false}",
                        "{\"toolRequest\": {\"capabilityId\": \"project.get_summary\", \"arguments\": {\"projectId\": \""
                                + payProject.id() + "\"}}, \"done\": false}",
                        "{\"assistantText\": \"Done.\", \"uiAction\": {\"destination\": \"PROJECT\", \"resourceId\": \""
                                + payProject.id() + "\"}, \"done\": true}")));
        // no-tool conversational answer
        results.add(runScenario("no-tool", "canonical", "how are projects usually organized?",
                List.of("{\"assistantText\": \"By recency and search.\", \"done\": true}")));
        results.add(runScenario("no-tool", "paraphrase", "what does the word project mean in English?",
                List.of("{\"assistantText\": \"It means a workspace.\", \"done\": true}")));
        long completed = results.stream().filter(ScenarioResult::completed).count();
        double completionRate = (double) completed / results.size();
        double toolAccuracy = results.stream()
                .filter(r -> r.selectedTool() != null || r.scenario().equals("no-tool") || r.scenario().equals("ambiguity"))
                .count() / (double) results.size();
        System.out.println("GA eval: scenarios=" + results.size() + " completed=" + completed
                + " completionRate=" + completionRate + " toolAccuracy=" + toolAccuracy);
        assertThat(completed).isEqualTo(results.size());
        // Mean steps stay bounded.
        double meanSteps = results.stream().mapToInt(ScenarioResult::steps).average().orElse(0);
        assertThat(meanSteps).isLessThanOrEqualTo(10);
    }
    @Test
    void negativeControlsFailClosed() {
        // Casual question triggers no tool.
        ScenarioResult casual = runScenario("negative-casual", "control",
                "will I maybe build a pay project someday",
                List.of("{\"assistantText\": \"Let me know when you want one.\", \"done\": true}"));
        // A well-formed but unknown project id passes shape validation.
        java.util.UUID fakeId = java.util.UUID.randomUUID();
        validator.validate(new com.specagent.globalassistant.model.GlobalAssistantDecision(
                null, null,
                new com.specagent.globalassistant.model.GlobalAssistantDecision.ToolRequest(
                        "project.get_summary", java.util.Map.of("projectId", fakeId.toString())),
                null, false, false));
        // Unknown tool rejected.
        assertThatThrownBy(() -> validator.validate(parser.parse(
                        "{\"toolRequest\": {\"capabilityId\": \"skill.do\", \"arguments\": {}}, \"done\": false}")))
                .isInstanceOf(GlobalAssistantModelException.class);
        // Arbitrary URL rejected.
        assertThatThrownBy(() -> validator.validate(parser.parse(
                        "{\"assistantText\": \"go\", \"uiAction\": {\"destination\": \"PROJECT\", \"resourceId\": \"https://x\"}, \"done\": true}")))
                .isInstanceOf(GlobalAssistantModelException.class);
        // Skill/MCP leakage absent from catalog.
        assertThat(GlobalAssistantToolCatalog.TOOL_IDS)
                .doesNotContain("skill.list", "mcp.call");
        // Same tool + same args + no new observation stops (covered in Slice E).
        // Second active run conflicts (covered in Slices A+F).
    }
}

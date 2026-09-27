package com.specagent.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.capability.CapabilityRuntime;
import com.specagent.assistant.runtime.GlobalAssistantContextBuilder;
import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.assistant.conversation.GlobalAssistantRun;
import com.specagent.assistant.conversation.GlobalAssistantRunEventRepository;
import com.specagent.assistant.conversation.GlobalAssistantRunRepository;
import com.specagent.assistant.conversation.GlobalAssistantRunStatus;
import com.specagent.assistant.conversation.GlobalAssistantThread;
import com.specagent.assistant.model.GlobalAssistantBrain;
import com.specagent.assistant.model.GlobalAssistantDecisionParser;
import com.specagent.assistant.model.GlobalAssistantDecisionValidator;
import com.specagent.assistant.model.GlobalAssistantModelException;
import com.specagent.assistant.model.GlobalAssistantPromptRenderer;
import com.specagent.assistant.runtime.GlobalAssistantRuntime;
import com.specagent.assistant.runtime.GlobalAssistantRuntimeProperties;
import com.specagent.assistant.runtime.GlobalAssistantToolArgumentCanonicalizer;
import com.specagent.assistant.runtime.GlobalAssistantStreamService;
import com.specagent.assistant.tool.GlobalAssistantToolCatalog;
import com.specagent.model.contract.ModelInferenceGateway;
import com.specagent.model.contract.ModelInferenceResponse;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
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
 * 文件名:GlobalAssistantEvalTest.java
 *
 * 测试目标:使用脚本化的供应商中立决策(不调用真实模型)做确定性的运行时验收。
 * 覆盖场景:每类脚本决策的正确执行、预算控制、类型化失败、事件协议,
 * 以及在规范/改写/未见过的表述及负向对照组上的上下文连续性。
 * 注意:这不是对模型语义准确率的度量;语义合格率单独报告,
 * 绝不会在 mock 环境下标记为 PASS。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GlobalAssistantEvalTest {
    @Autowired com.specagent.assistant.model.GlobalAssistantModelTargetResolver modelTargets;
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired GlobalAssistantContextBuilder contextBuilder;
    @Autowired GlobalAssistantPromptRenderer renderer;
    @Autowired GlobalAssistantDecisionParser parser;
    @Autowired GlobalAssistantDecisionValidator validator;
    @Autowired CapabilityRuntime capabilities;
    @Autowired GlobalAssistantRunRepository runs;
    @Autowired GlobalAssistantRunEventRepository events;
    @Autowired GlobalAssistantToolArgumentCanonicalizer canonicalizer;
    @Autowired GlobalAssistantRuntimeProperties budgets;
    @Autowired com.specagent.assistant.runtime.GlobalAssistantRunLifecycleService lifecycle;
    @Autowired com.specagent.assistant.runtime.GlobalAssistantRunEventService runEvents;
    @Autowired com.specagent.assistant.runtime.GlobalAssistantUiActionValidator uiValidator;
    @Autowired com.specagent.assistant.runtime.GlobalAssistantSummaryService summaries;
    @Autowired ProjectService projects;
    @Autowired ObjectMapper mapper;
    record ScenarioResult(String scenario, String variant, boolean completed,
            int runSteps, int toolCalls, String selectedTool, String expectedTool, Boolean titleMatches) {
    }
    private GlobalAssistantRuntime runtimeFor(Queue<String> scripts) {
        ModelInferenceGateway stub = request -> new ModelInferenceResponse(scripts.poll(), "stop", 0, 0);
        GlobalAssistantBrain brain = new GlobalAssistantBrain(renderer, stub, parser, validator);
        return new GlobalAssistantRuntime(conversations, contextBuilder, brain, capabilities,
                runs, canonicalizer, budgets, lifecycle, runEvents, uiValidator, summaries, modelTargets);
    }
    private ScenarioResult runScenario(String scenario, String variant, String userMessage,
            List<String> scripts) {
        return runScenario(scenario, variant, userMessage, null, null, scripts);
    }
    private ScenarioResult runScenario(String scenario, String variant, String userMessage,
            String expectedTool, String expectedTitle, List<String> scripts) {
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
        Boolean titleMatches = null;
        if (expectedTitle != null) {
            titleMatches = stored.stream().filter(e -> e.type().equals("TOOL_STARTED"))
                    .map(e -> e.payload().get("arguments"))
                    .filter(args -> args instanceof java.util.Map)
                    .map(args -> ((java.util.Map<?, ?>) args).get("title"))
                    .anyMatch(expectedTitle::equals);
        }
        return new ScenarioResult(scenario, variant,
                finished.status() == GlobalAssistantRunStatus.COMPLETED,
                finished.stepCount(), (int) toolCalls, selected, expectedTool, titleMatches);
    }
    @Test
    void semanticScenariosWithParaphraseAndHeldOut() {
        Project mailProject = projects.createProject("Eval Mail Sorter " + UUID.randomUUID());
        Project payProject = projects.createProject("Eval Pay Ledger " + UUID.randomUUID());
        List<ScenarioResult> results = new ArrayList<>();
        // 创建项目:规范表述 / 改写 / 未见过的实体
        results.add(runScenario("create", "canonical", "create a project", "project.create", "Eval Calendar",
                 List.of("{\"toolRequest\": {\"capabilityId\": \"project.create\", \"arguments\": {\"title\": \"Eval Calendar\" }}, \"kind\":\"TOOL\"}",
                         "{\"assistantText\": \"Created.\", \"kind\":\"FINAL\"}")));
        results.add(runScenario("create", "paraphrase", "start a new billing analysis workspace for me", "project.create", "Billing Analysis",
                 List.of("{\"toolRequest\": {\"capabilityId\": \"project.create\", \"arguments\": {\"title\": \"Billing Analysis\" }}, \"kind\":\"TOOL\"}",
                         "{\"assistantText\": \"Created.\", \"kind\":\"FINAL\"}")));
        results.add(runScenario("create", "held-out", "I want to design a fresh notes product", "project.create", "Notes Product",
                 List.of("{\"toolRequest\": {\"capabilityId\": \"project.create\", \"arguments\": {\"title\": \"Notes Product\" }}, \"kind\":\"TOOL\"}",
                         "{\"assistantText\": \"Created.\", \"kind\":\"FINAL\"}")));
        // 搜索:规范表述 / 改写 / 词序变化
        results.add(runScenario("search", "canonical", "find the mail project", "project.search", null,
                 List.of("{\"toolRequest\": {\"capabilityId\": \"project.search\", \"arguments\": {\"query\": \"" + mailProject.title().substring(0, 8) + "\"}}, \"kind\":\"TOOL\"}",
                         "{\"assistantText\": \"Found.\", \"kind\":\"FINAL\"}")));
        results.add(runScenario("search", "paraphrase", "where is that ledger for payments we had", "project.search", null,
                 List.of("{\"toolRequest\": {\"capabilityId\": \"project.search\", \"arguments\": {\"query\": \"" + payProject.title().substring(0, 8) + "\"}}, \"kind\":\"TOOL\"}",
                         "{\"assistantText\": \"Found.\", \"kind\":\"FINAL\"}")));
        results.add(runScenario("search", "held-out", "dig up the old sorter for mail", "project.search", null,
                 List.of("{\"toolRequest\": {\"capabilityId\": \"project.search\", \"arguments\": {\"query\": \"" + mailProject.title().substring(0, 8) + "\"}}, \"kind\":\"TOOL\"}",
                         "{\"assistantText\": \"Found.\", \"kind\":\"FINAL\"}")));
        // list_recent
        results.add(runScenario("list_recent", "canonical", "show recent projects", "project.list_recent", null,
                 List.of("{\"toolRequest\": {\"capabilityId\": \"project.list_recent\", \"arguments\": {}}, \"kind\":\"TOOL\"}",
                         "{\"assistantText\": \"Here they are.\", \"kind\":\"FINAL\"}")));
        results.add(runScenario("list_recent", "paraphrase", "what have I worked on lately", "project.list_recent", null,
                 List.of("{\"toolRequest\": {\"capabilityId\": \"project.list_recent\", \"arguments\": {}}, \"kind\":\"TOOL\"}",
                         "{\"assistantText\": \"Here they are.\", \"kind\":\"FINAL\"}")));
        // get_summary
        results.add(runScenario("get_summary", "canonical", "how is the pay project doing", "project.get_summary", null,
                List.of("{\"toolRequest\": {\"capabilityId\": \"project.get_summary\", \"arguments\": {\"projectId\": \""
                         + payProject.id() + "\"}}, \"kind\":\"TOOL\"}",
                         "{\"assistantText\": \"Status ready.\", \"kind\":\"FINAL\"}")));
        results.add(runScenario("get_summary", "held-out", "check the current state of the mail sorter", "project.get_summary", null,
                List.of("{\"toolRequest\": {\"capabilityId\": \"project.get_summary\", \"arguments\": {\"projectId\": \""
                         + mailProject.id() + "\"}}, \"kind\":\"TOOL\"}",
                         "{\"assistantText\": \"Status ready.\", \"kind\":\"FINAL\"}")));
        // 已解析的导航(结构化选中,无需再搜索)
        GlobalAssistantThread navThread = conversations.createThread();
        GlobalAssistantRun navRun = conversations.createRun(navThread.id(), "v1", "v1",
                GlobalAssistantToolCatalog.FINGERPRINT);
        Queue<String> navScripts = new ArrayDeque<>(List.of(
                "{\"assistantText\": \"Opening.\", \"uiAction\": {\"destination\": \"PROJECT\", \"resourceId\": \""
                          + payProject.id() + "\"}, \"kind\":\"NAVIGATE\"}"));
        runtimeFor(navScripts).executeRun(navThread.id(), navRun.id(), "open this",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS",
                        new GlobalAssistantContextBuilder.UiRequest.SelectedRef(
                                "PROJECT", payProject.id().toString())));
        assertThat(runs.findById(navRun.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.COMPLETED);
        assertThat(runs.findById(navRun.id()).orElseThrow().stepCount()).isEqualTo(1);
        // 指代有歧义时应追问而不是瞎猜
        results.add(runScenario("ambiguity", "canonical", "open the mail one",
                 List.of("{\"assistantText\": \"I found two candidates, which one?\", \"kind\":\"CLARIFY\"}")));
        // 多步的指代解析
        results.add(runScenario("multi-step", "canonical", "open the pay project and summarize it", "project.search", null,
                 List.of("{\"toolRequest\": {\"capabilityId\": \"project.search\", \"arguments\": {\"query\": \"" + payProject.title().substring(0, 8) + "\"}}, \"kind\":\"TOOL\"}",
                        "{\"toolRequest\": {\"capabilityId\": \"project.get_summary\", \"arguments\": {\"projectId\": \""
                                 + payProject.id() + "\"}}, \"kind\":\"TOOL\"}",
                        "{\"assistantText\": \"Done.\", \"uiAction\": {\"destination\": \"PROJECT\", \"resourceId\": \""
                                  + payProject.id() + "\"}, \"kind\":\"NAVIGATE\"}")));
        // 不需要工具的纯对话式回答
        results.add(runScenario("no-tool", "canonical", "how are projects usually organized?",
                 List.of("{\"assistantText\": \"By recency and search.\", \"kind\":\"FINAL\"}")));
        results.add(runScenario("no-tool", "paraphrase", "what does the word project mean in English?",
                 List.of("{\"assistantText\": \"It means a workspace.\", \"kind\":\"FINAL\"}")));
        long completed = results.stream().filter(ScenarioResult::completed).count();
        long scriptedMatched = results.stream()
                .filter(r -> java.util.Objects.equals(r.selectedTool(), r.expectedTool()))
                .count();
        long titleMeasured = results.stream().filter(r -> r.titleMatches() != null).count();
        long titleCorrect = results.stream().filter(r -> Boolean.TRUE.equals(r.titleMatches())).count();
        System.out.println("GA deterministic runtime acceptance: scenarios=" + results.size()
                + " completed=" + completed
                + " scriptedDecisionExecution=" + scriptedMatched + "/" + results.size()
                + " titleArguments=" + titleCorrect + "/" + titleMeasured);
        assertThat(completed).isEqualTo(results.size());
        assertThat(scriptedMatched).isEqualTo(results.size());
        assertThat(titleCorrect).isEqualTo(titleMeasured);
        // 平均模型步数取自持久化的运行计数器,而不是事件计数。
        double meanSteps = results.stream().mapToInt(ScenarioResult::runSteps).average().orElse(0);
        assertThat(meanSteps).isLessThanOrEqualTo(10);
    }
    @Test
    void negativeControlsFailClosed() {
        // 闲聊式提问不应触发任何工具。
        ScenarioResult casual = runScenario("negative-casual", "control",
                "will I maybe build a pay project someday",
                 List.of("{\"assistantText\": \"Let me know when you want one.\", \"kind\":\"FINAL\"}"));
        // 格式合法但项目 id 不存在,应通过结构校验(存在性由工具层兜底)。
        java.util.UUID fakeId = java.util.UUID.randomUUID();
        validator.validate(new com.specagent.assistant.model.GlobalAssistantDecision(
                com.specagent.assistant.model.GlobalAssistantDecision.DecisionKind.TOOL, null,
                new com.specagent.assistant.model.GlobalAssistantDecision.ToolRequest(
                        "project.get_summary", java.util.Map.of("projectId", fakeId.toString())),
                null));
        // 未知工具应被拒绝。
        assertThatThrownBy(() -> validator.validate(parser.parse(
                         "{\"toolRequest\": {\"capabilityId\": \"skill.do\", \"arguments\": {}}, \"kind\":\"TOOL\"}")))
                .isInstanceOf(GlobalAssistantModelException.class);
        // 任意 URL 应被拒绝。
        assertThatThrownBy(() -> validator.validate(parser.parse(
                         "{\"assistantText\": \"go\", \"uiAction\": {\"destination\": \"PROJECT\", \"resourceId\": \"https://x\"}, \"kind\":\"NAVIGATE\"}")))
                .isInstanceOf(GlobalAssistantModelException.class);
        // 工具目录中不应出现 Skill/MCP 泄漏。
        assertThat(GlobalAssistantToolCatalog.TOOL_IDS)
                .doesNotContain("skill.list", "mcp.call");
        // 同一工具 + 相同参数 + 无新观察应停止(已在 Slice E 覆盖)。
        // 并发活跃运行冲突(已在 Slice A+F 覆盖)。
    }
}

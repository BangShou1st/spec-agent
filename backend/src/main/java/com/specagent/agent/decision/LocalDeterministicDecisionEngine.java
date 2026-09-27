package com.specagent.agent.decision;

import com.specagent.agent.protocol.ActionProposal;
import com.specagent.agent.protocol.AgentArtifactResponse;
import com.specagent.agent.protocol.AgentProtocol;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.AgentResponseEnvelope;
import com.specagent.agent.protocol.CapabilityDescriptor;
import com.specagent.agent.protocol.ObservationView;
import com.specagent.agent.protocol.ProposedClaim;
import com.specagent.agent.protocol.StateUpdateResult;
import com.specagent.agent.protocol.UsageView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 文件名:LocalDeterministicDecisionEngine.java
 *
 * 用途:JVM 内的确定性决策引擎,只有显式配置
 * {@code spec.agent.brain.engine=fake} 时才会被选中。它产出与 Python Brain
 * 的 fake model client 共享的规范 fake 输出(参见
 * {@code contracts/fixtures/fake-model-*.json}),并把输出送入与远程引擎
 * 相同的 fail-closed 校验器,因此测试无需 HTTP 就能走完全一致的契约路径。
 *
 * 正常产品配置永远不会选中该引擎。
 */
@Component
@ConditionalOnProperty(name = "spec.agent.brain.engine", havingValue = "fake")
public class LocalDeterministicDecisionEngine implements AgentDecisionEngine {

    private final DeterministicEngineFaultPlan faultPlan;

    public LocalDeterministicDecisionEngine(DeterministicEngineFaultPlan faultPlan) {
        this.faultPlan = faultPlan;
    }

    @Override
    public AgentResponseEnvelope runStateUpdate(AgentRequestEnvelope request) {
        // 显式的仅测试失败钩子:除非提交的回答文本带有指令,否则完全
        // 不生效(见 DeterministicEngineFaultPlan)。回答文本还可武装对
        // 后续独立起草 run 的确定性失败([[fail-decision:N]])与对规格
        // 生成 run 的确定性失败([[fail-artifact:N]]),用于失败恢复入口
        // 的浏览器验证。
        faultPlan.failStateUpdateIfDirected(request);
        faultPlan.armDecisionFaultIfDirected(request);
        faultPlan.armArtifactFaultIfDirected(request);
        faultPlan.armDecisionDelayIfDirected(request);
        AgentResponseEnvelope response = new AgentResponseEnvelope(
                AgentProtocol.DECISION_PROTOCOL_VERSION,
                request.runId(),
                new StateUpdateResult(List.of(new ProposedClaim(
                        "goal",
                        "The user clarified the main outcome.",
                        "confirmed",
                        0.9,
                        List.of()))),
                null,
                null,
                new UsageView(1, List.of()),
                Map.of());
        AgentBrainResponseValidator.validateStateUpdate(request, response);
        return response;
    }

    @Override
    public AgentResponseEnvelope runDecision(AgentRequestEnvelope request) {
        // 显式的仅测试钩子:回答周期内部的 DECISION(ANSWER_SUBMITTED)
        // 与只读 NODE_QUERY 不受影响,独立起草/续跑/换题按武装的预算
        // 延迟([[delay-decision-ms:N]])与失败([[fail-decision:N]])。
        faultPlan.delayDecisionIfDirected(request);
        faultPlan.failDecisionIfDirected(request);
        UUID snapshotId = UUID.fromString(request.snapshot().snapshotId());
        if ("NODE_QUERY".equals(request.event().kind())) {
            // 确定性 E2E 变更路径:显式的"建立语义关联"指令会产生一个
            // 可确认的 CONNECT_NODE 提案,连接谱系中的前两个节点。其他任何
            // 查询输入都走下方的只读响应——查询契约保持无副作用。
            String queryText = request.event().freeText() == null ? "" : request.event().freeText();
            if (queryText.contains("语义关联")) {
                List<String> lineageNodeRefs = request.snapshot().lineage().stream()
                        .limit(2)
                        .map(entry -> "node:" + entry.node().id())
                        .toList();
                if (lineageNodeRefs.size() >= 2) {
                    AgentResponseEnvelope connect = decisionResponse(request,
                            new ObservationView(
                                    List.of("Two nodes suggest a semantic relation."),
                                    List.of(), List.of(), List.of()),
                            new ActionProposal(
                                    "CONNECT_NODE",
                                    Map.of(
                                            "relationClass", "SEMANTIC",
                                            "relationType", "RELATED_TO",
                                            "sourceRef", lineageNodeRefs.get(0),
                                            "targetRef", lineageNodeRefs.get(1)),
                                    snapshotId,
                                    request.snapshot().contextHash(),
                                    List.of(),
                                    UUID.randomUUID(),
                                    request.runId().toString(),
                                    List.of()));
                    AgentBrainResponseValidator.validateDecision(request, connect);
                    return connect;
                }
            }
            // 确定性的上下文回答:查询路径期望只读响应,绝不能改动图。
            AgentResponseEnvelope respond = decisionResponse(request,
                    new ObservationView(
                            List.of("The node context grounds the answer."),
                            List.of(), List.of(), List.of()),
                    new ActionProposal(
                            "RESPOND_TO_USER",
                            Map.of("message", "关于该节点：" + request.event().freeText()),
                            snapshotId,
                            request.snapshot().contextHash(),
                            List.of(),
                            UUID.randomUUID(),
                            request.runId().toString(),
                            List.of()));
            AgentBrainResponseValidator.validateDecision(request, respond);
            return respond;
        }
        // 确定性的能力调用路径:挑选声明支持的上下文类型与投影谱系匹配的
        // 能力。工作区级的检索能力刻意不声明节点类型支持,且要求真实的
        // query 参数,所以这个 fixture 引擎在普通回答循环中不会猜用它们。
        CapabilityDescriptor candidate = request.snapshot().availableCapabilities().stream()
                .filter(descriptor -> supportsLineage(descriptor, request))
                .findFirst()
                .orElseGet(() -> request.snapshot().availableCapabilities().stream()
                        .filter(descriptor -> !requiresQuery(descriptor))
                        .findFirst()
                        .orElse(null));
        if (candidate != null) {
            String nodeRef = request.snapshot().lineage().stream()
                    .filter(entry -> !"INTERACTION".equals(entry.node().kind()))
                    .map(entry -> "node:" + entry.node().id())
                    .findFirst()
                    .orElse(null);
            if (nodeRef != null) {
                AgentResponseEnvelope invoke = decisionResponse(request,
                        new ObservationView(
                                List.of("A resource is available in the lineage."),
                                List.of(), List.of(), List.of()),
                        new ActionProposal(
                                "INVOKE_CAPABILITY",
                                Map.of("capabilityId", candidate.id(),
                                       "arguments", Map.of("nodeRef", nodeRef)),
                                snapshotId,
                                request.snapshot().contextHash(),
                                List.of(),
                                UUID.randomUUID(),
                                request.runId().toString(),
                                List.of()));
                AgentBrainResponseValidator.validateDecision(request, invoke);
                return invoke;
            }
        }
        // 携带自由文本的 CONTINUE 事件是一次定向修订(例如要求替换):
        // 确定性提案用一个不同的问题来反映该指示,而不是复用规范的草稿问题。
        String questionText = "What is the most important outcome?";
        String purpose = "This clarifies the primary requirement goal.";
        String optionLabel = "Clarify the primary goal";
        if ("CONTINUE".equals(request.event().kind())
                && request.event().freeText() != null
                && !request.event().freeText().isBlank()) {
            questionText = "A sharper version of the rejected question.";
            purpose = "This follows the user's direction.";
            optionLabel = "Clarify the primary goal";
        } else if (!"NODE_QUERY".equals(request.event().kind())) {
            // 确定性的澄清阶梯:fake 绝不能重复一个已被回答的问题,否则
            // 强制的 RESOLVED_BLOCKER 规则会在 run 到达终态之前判其失败。
            // 零个已回答问题时保持规范的第一问;每有一个已回答问题就前进
            // 一级;兜底问题在与 gate 相同的归一化规则下避开所有已回答的
            // 文本。
            FollowUpQuestion followUp = selectFollowUpQuestion(request);
            questionText = followUp.questionText();
            purpose = followUp.purpose();
            optionLabel = followUp.optionLabel();
        }
        AgentResponseEnvelope response = decisionResponse(request,
                new ObservationView(
                        List.of("The user clarified the main outcome."),
                        List.of("The user must confirm scope boundaries."),
                        List.of(),
                        List.of()),
                new ActionProposal(
                        "REQUEST_USER_INPUT",
                        Map.of(
                                "questionText", questionText,
                                "purpose", purpose,
                                "options", List.of(Map.of("label", optionLabel)),
                                "allowFreeAnswer", true),
                        snapshotId,
                        request.snapshot().contextHash(),
                        List.of(),
                        UUID.randomUUID(),
                        request.runId().toString(),
                        List.of()));
        AgentBrainResponseValidator.validateDecision(request, response);
        return response;
    }

    private boolean supportsLineage(CapabilityDescriptor descriptor,
                                    AgentRequestEnvelope request) {
        if (descriptor.supports().isEmpty()) {
            return false;
        }
        return descriptor.supports().stream().anyMatch(support ->
                request.snapshot().lineage().stream()
                        .anyMatch(entry -> supportMatches(support, entry.node().kind())));
    }

    private boolean supportMatches(String support, String contextKind) {
        int separator = support.indexOf(':');
        String supportKind = separator < 0 ? support : support.substring(0, separator);
        return supportKind.equalsIgnoreCase(contextKind);
    }

    private boolean requiresQuery(CapabilityDescriptor descriptor) {
        Map<String, Object> schema = descriptor.inputSchema();
        Object properties = schema.get("properties");
        if (properties instanceof Map<?, ?> propertyMap
                && propertyMap.containsKey("query")
                && schema.get("required") instanceof List<?> required
                && required.stream().anyMatch("query"::equals)) {
            return true;
        }
        // 运行时能力描述符也会使用紧凑的项目形态:
        // {"query": {"type":"string", "required":true}}。保持 fake 引擎
        // 通用,使其永远不为无法安全合成的必填参数捏造 nodeRef。
        Object queryDefinition = schema.get("query");
        if (queryDefinition instanceof Map<?, ?> definition) {
            Object required = definition.get("required");
            return Boolean.TRUE.equals(required) || "true".equalsIgnoreCase(String.valueOf(required));
        }
        return false;
    }

    /**
     * 与 Python Brain 的 fake model client 共享的确定性澄清阶梯:选择第一个
     * 归一化文本不属于已回答谱系问题的候选。第一级就是规范的 fake 问题,
     * 保证零回答时的行为不变;当所有具名级别都被用过后,编号兜底问题会
     * 持续越过任何已回答的文本。
     */
    static FollowUpQuestion selectFollowUpQuestion(AgentRequestEnvelope request) {
        Set<String> answered = new HashSet<>();
        request.snapshot().lineage().stream()
                .filter(entry -> entry.answer() != null)
                .map(entry -> normalizeQuestion(entry.node().body().text()))
                .forEach(answered::add);
        List<FollowUpQuestion> ladder = List.of(
                new FollowUpQuestion(
                        "What is the most important outcome?",
                        "This clarifies the primary requirement goal.",
                        "Clarify the primary goal"),
                new FollowUpQuestion(
                        "What is the next most important outcome?",
                        "This clarifies the next requirement goal.",
                        "Clarify the next goal"),
                new FollowUpQuestion(
                        "What scope boundaries must be confirmed?",
                        "This confirms the scope boundaries.",
                        "Confirm the scope boundaries"));
        for (FollowUpQuestion candidate : ladder) {
            if (!answered.contains(normalizeQuestion(candidate.questionText()))) {
                return candidate;
            }
        }
        int followUp = answered.size() + 1;
        while (true) {
            FollowUpQuestion candidate = new FollowUpQuestion(
                    "What else should be clarified next? (follow-up " + followUp + ")",
                    "This clarifies the remaining requirement details.",
                    "Clarify the remaining details");
            if (!answered.contains(normalizeQuestion(candidate.questionText()))) {
                return candidate;
            }
            followUp += 1;
        }
    }

    /** 确定性澄清阶梯中的一级。 */
    record FollowUpQuestion(String questionText, String purpose, String optionLabel) {
    }

    static String normalizeQuestion(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .strip()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private AgentResponseEnvelope decisionResponse(AgentRequestEnvelope request,
                                                   ObservationView observation,
                                                   ActionProposal proposal) {
        if (AgentProtocol.INPUT_PROTOCOL_VERSION_V3.equals(request.protocolVersion())) {
            return new AgentResponseEnvelope(
                    AgentProtocol.DECISION_PROTOCOL_VERSION_V3,
                    request.runId(), null, observation, proposal,
                    new UsageView(1, List.of()), Map.of(),
                    request.actionEligibility().version(),
                    request.actionEligibility().basisHash(),
                    proposal.sourceRefs());
        }
        return new AgentResponseEnvelope(
                AgentProtocol.DECISION_PROTOCOL_VERSION_V2,
                request.runId(), null, observation, proposal,
                new UsageView(1, List.of()), Map.of());
    }

    @Override
    public AgentArtifactResponse runArtifactGeneration(AgentRequestEnvelope request) {
        // 显式的仅测试失败钩子:只有有效历史包含已武装 [[fail-artifact:N]]
        // 指令的已回答节点时才引爆(见 DeterministicEngineFaultPlan)。
        faultPlan.failArtifactIfDirected(request);
        String contextRef = request.snapshot().allowedSourceRefs().stream()
                .filter(ref -> ref.startsWith("context:"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Artifact generation requires a context ref in the snapshot"));
        // 与 Python Brain 的 fake model client 共享的确定性 fake 输出
        // (contracts/fixtures/fake-model-artifact-output.json);每个小节
        // 都引用受信快照自身的 context ref。
        AgentArtifactResponse response = new AgentArtifactResponse(
                AgentProtocol.ARTIFACT_PROTOCOL_VERSION,
                request.runId(),
                new AgentArtifactResponse.ArtifactGenerationResult(
                        "spec_snapshot",
                        List.of(
                                new AgentArtifactResponse.ArtifactSection(
                                        "Overview",
                                        "用户澄清了主要目标：明确最重要的成果。",
                                        List.of(contextRef)),
                                new AgentArtifactResponse.ArtifactSection(
                                        "Open Questions",
                                        "范围边界尚未确认，需要用户进一步澄清。",
                                        List.of(contextRef))),
                        List.of("范围边界尚未确认。")),
                new UsageView(1, List.of()));
        AgentBrainResponseValidator.validateArtifact(request, response);
        return response;
    }
}

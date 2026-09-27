package com.specagent.agent.decision;

import com.specagent.agent.protocol.ActionFamily;
import com.specagent.agent.protocol.AgentProtocol;
import com.specagent.agent.protocol.AgentArtifactResponse;
import com.specagent.agent.protocol.AgentInputSnapshot;
import com.specagent.agent.protocol.AgentContractException;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.AgentResponseEnvelope;
import com.specagent.agent.protocol.ClaimVocabulary;
import com.specagent.agent.protocol.ProposedClaim;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 文件名:AgentBrainResponseValidator.java
 *
 * 用途:对 Brain 的每一次响应做 fail-closed 校验,任何响应必须先通过校验
 * 才允许落库或执行。Brain 被视为不可信输入:凡是伪造运行时身份、引用冻结
 * 快照之外的来源、回显过期的 base context、绕过未消解的需求冲突、超出调用
 * 预算,或携带未知动作族(action family)的响应,都会被整体拒绝。
 *
 * 纯契约逻辑:不依赖仓储、网关,也不做任何持久化。
 */
public final class AgentBrainResponseValidator {

    private static final int MAX_CLAIMS = 100;
    private static final int MAX_OBSERVATION_ENTRIES = 50;
    private static final int MAX_ENTRY_LENGTH = 2000;
    private static final int MAX_SOURCE_REFS = 200;
    private static final Set<String> FORBIDDEN_PAYLOAD_KEYS = Set.of(
            "id", "nodeId", "optionId", "sourceNodeId", "sourceAnswerId", "runId");

    private AgentBrainResponseValidator() {
    }

    /** 针对 {@code POST /v1/state-updates} 的响应,对照请求做校验。 */
    public static void validateStateUpdate(AgentRequestEnvelope request,
                                           AgentResponseEnvelope response) {
        validateCommon(request, response);
        if (response.stateUpdate() == null) {
            throw new AgentContractException("State update response requires stateUpdate");
        }
        if (response.actionProposal() != null) {
            throw new AgentContractException(
                    "State update response must not carry an action proposal");
        }
        List<ProposedClaim> claims = response.stateUpdate().claims();
        if (claims.size() > MAX_CLAIMS) {
            throw new AgentContractException("Too many proposed claims: " + claims.size());
        }
        for (ProposedClaim claim : claims) {
            validateClaim(claim, request);
        }
    }

    /**
     * 针对产物(artifact)生成响应,对照请求做校验:产物必须是受支持的类型,
     * 每个小节(section)必须非空且至少引用一个允许的来源 ref,并且派生内容
     * 中任何位置都不允许出现运行时拥有的身份字段。
     */
    public static void validateArtifact(AgentRequestEnvelope request,
                                        AgentArtifactResponse response) {
        if (!request.runId().equals(response.runId())) {
            throw new AgentContractException(
                    "Response runId does not match the request: " + response.runId());
        }
        if (response.usage() != null && request.decisionBudget() != null) {
            int calls = response.usage().modelCalls();
            if (calls < 0 || calls > request.decisionBudget().maxModelCalls()) {
                throw new AgentContractException(
                        "Response model call count outside the decision budget: " + calls);
            }
        }
        if (response.artifact() == null) {
            throw new AgentContractException("Artifact response requires an artifact");
        }
        var result = response.artifact();
        if (!"spec_snapshot".equals(result.artifactType())) {
            throw new AgentContractException(
                    "Unsupported artifact type: " + result.artifactType());
        }
        if (result.sections().isEmpty()) {
            throw new AgentContractException("Artifact requires at least one section");
        }
        Set<String> allowed = Set.copyOf(request.snapshot().allowedSourceRefs());
        for (var section : result.sections()) {
            requireNonBlank("section title", section.title());
            requireNonBlank("section content", section.content());
            List<String> refs = section.sourceRefs() == null
                    ? List.of() : section.sourceRefs();
            if (refs.isEmpty()) {
                throw new AgentContractException(
                        "Artifact section requires source references: " + section.title());
            }
            if (refs.size() > MAX_SOURCE_REFS) {
                throw new AgentContractException("Too many source references in section");
            }
            for (String ref : refs) {
                if (!allowed.contains(ref)) {
                    throw new AgentContractException(
                            "Section referenced a source outside the allowed snapshot refs: "
                                    + ref);
                }
            }
        }
        if (result.unresolvedItems().size() > MAX_OBSERVATION_ENTRIES) {
            throw new AgentContractException("Too many unresolved items");
        }
        for (String item : result.unresolvedItems()) {
            requireNonBlank("unresolved item", item);
            if (item.length() > MAX_ENTRY_LENGTH) {
                throw new AgentContractException(
                        "Unresolved item exceeds the length limit");
            }
        }
    }

    /** 针对 {@code POST /v1/decisions} 的响应,对照请求做校验。 */
    public static void validateDecision(AgentRequestEnvelope request,
                                        AgentResponseEnvelope response) {
        validateCommon(request, response);
        if (response.actionProposal() == null) {
            throw new AgentContractException("Decision response requires an actionProposal");
        }
        if (response.observation() == null) {
            throw new AgentContractException("Decision response requires an observation");
        }
        validateObservation(response.observation());
        validateProposal(request, response.actionProposal());
        validateEligibilityContract(request, response);
        validateConflictAction(request, response);
    }

    private static void validateEligibilityContract(AgentRequestEnvelope request,
                                                    AgentResponseEnvelope response) {
        boolean requestV3 = AgentProtocol.INPUT_PROTOCOL_VERSION_V3
                .equals(request.protocolVersion());
        boolean responseV3 = AgentProtocol.DECISION_PROTOCOL_VERSION_V3
                .equals(response.protocolVersion());
        if (requestV3 != responseV3) {
            throw new AgentContractException(
                    "Decision request/response eligibility protocol versions do not match");
        }
        if (!requestV3) {
            return;
        }
        if (!request.actionEligibility().version()
                .equals(response.selectedEligibilityVersion())) {
            throw new AgentContractException(
                    "Selected eligibility version does not match the request");
        }
        if (!request.actionEligibility().basisHash()
                .equals(response.selectedEligibilityBasisHash())) {
            throw new AgentContractException(
                    "Selected eligibility basisHash does not match the request");
        }
        validateSourceRefs(response.eligibilityEvidenceRefs(), request.snapshot());
        if (!request.actionEligibility().eligibleFamilies()
                .contains(response.actionProposal().actionFamily())) {
            throw new AgentContractException(
                    "Selected action family is not in the request eligibility mask");
        }
    }

    private static void validateCommon(AgentRequestEnvelope request,
                                       AgentResponseEnvelope response) {
        if (!request.runId().equals(response.runId())) {
            throw new AgentContractException(
                    "Response runId does not match the request: " + response.runId());
        }
        if (response.usage() != null && request.decisionBudget() != null) {
            int calls = response.usage().modelCalls();
            if (calls < 0 || calls > request.decisionBudget().maxModelCalls()) {
                throw new AgentContractException(
                        "Response model call count outside the decision budget: " + calls);
            }
        }
    }

    private static void validateClaim(ProposedClaim claim, AgentRequestEnvelope request) {
        requireEnum("claim kind", claim.kind(), ClaimVocabulary.KINDS);
        requireNonBlank("claim text", claim.text());
        requireEnum("claim status", claim.status(), ClaimVocabulary.STATUSES);
        if (claim.confidence() != null && (claim.confidence() < 0.0 || claim.confidence() > 1.0)) {
            throw new AgentContractException(
                    "Claim confidence must be between 0.0 and 1.0: " + claim.confidence());
        }
        validateSourceRefs(claim.sourceRefs(), request.snapshot());
    }

    private static void validateObservation(com.specagent.agent.protocol.ObservationView observation) {
        validateEntries("known", observation.known());
        validateEntries("unknowns", observation.unknowns());
        validateEntries("conflicts", observation.conflicts());
        validateEntries("risks", observation.risks());
    }

    private static void validateEntries(String name, List<String> entries) {
        if (entries.size() > MAX_OBSERVATION_ENTRIES) {
            throw new AgentContractException("Too many " + name + " entries: " + entries.size());
        }
        for (String entry : entries) {
            requireNonBlank(name + " entry", entry);
            if (entry.length() > MAX_ENTRY_LENGTH) {
                throw new AgentContractException(name + " entry exceeds the length limit");
            }
        }
    }

    private static void validateProposal(AgentRequestEnvelope request,
                                         com.specagent.agent.protocol.ActionProposal proposal) {
        ActionFamily family;
        try {
            family = ActionFamily.fromCode(proposal.actionFamily());
        } catch (IllegalArgumentException ex) {
            throw new AgentContractException(ex.getMessage());
        }
        if (proposal.proposalId() == null) {
            throw new AgentContractException("Proposal must carry a proposalId");
        }
        if (proposal.idempotencyKey() == null || proposal.idempotencyKey().isBlank()) {
            throw new AgentContractException("Proposal must carry a non-blank idempotencyKey");
        }
        AgentInputSnapshot snapshot = request.snapshot();
        UUID snapshotId = UUID.fromString(snapshot.snapshotId());
        if (!snapshotId.equals(proposal.baseContextSnapshotId())) {
            throw new AgentContractException(
                    "Proposal baseContextSnapshotId does not match the request snapshot");
        }
        if (!snapshot.contextHash().equals(proposal.baseContextHash())) {
            throw new AgentContractException(
                    "Proposal baseContextHash is stale or does not match the request snapshot");
        }
        validateSourceRefs(proposal.sourceRefs(), snapshot);
        validateSourceRefs(proposal.anchorRefs(), snapshot);
        validatePayload(family, proposal.payload(), snapshot);
    }

    /**
     * 未消解的冲突必须被如实呈现:响应必须携带非空的
     * {@code observation.conflicts}。ACTION 选择本身在此不做限制(Slice 4):
     * 只读能力调用与向用户提问同样可接受——执行安全由 Runtime 策略、
     * 过期上下文、权限以及图不变量等关卡负责,而不是由本契约校验负责。
     * NODE_QUERY 完全豁免:它属于上下文读取流程,即使工作区仍存在未消解的
     * 规划冲突,也必须保持可用。
     */
    private static void validateConflictAction(AgentRequestEnvelope request,
                                               AgentResponseEnvelope response) {
        if (request.event() != null && "NODE_QUERY".equals(request.event().kind())) {
            return;
        }
        boolean unresolvedConflict = request.snapshot().effectiveClaims().stream()
                .anyMatch(claim -> "conflict".equals(claim.kind())
                        && "unresolved".equals(claim.status()));
        if (!unresolvedConflict) {
            return;
        }
        if (response.observation().conflicts().isEmpty()) {
            throw new AgentContractException(
                    "unresolved conflict requires a non-empty observation.conflicts");
        }
    }

    private static final java.util.List<String> REF_PREFIXES =
            java.util.List.of("node:", "answer:", "patch:", "context:", "route:");

    /**
     * INVOKE_CAPABILITY 的 payload:一个非空 capabilityId 加上一个可选的
     * arguments 对象。任何形似运行时 ref 的参数值都必须落在快照允许的
     * refs 之内——这是一条通用的防夹带(anti-smuggling)规则,不绑定任何
     * 具体参数名。未知 capability id 由策略拒绝(执行时 fail-closed);
     * 本校验器只检查线上传输的形状。
     */
    private static void validateInvokeCapability(Map<String, Object> payload,
                                                 AgentInputSnapshot snapshot) {
        requireNonBlank("capabilityId", asString(payload.get("capabilityId"), "capabilityId"));
        Object arguments = payload.get("arguments");
        if (arguments == null) {
            return;
        }
        if (!(arguments instanceof Map<?, ?> argumentMap)) {
            throw new AgentContractException("INVOKE_CAPABILITY arguments must be an object");
        }
        for (Object value : argumentMap.values()) {
            if (value instanceof String candidate
                    && REF_PREFIXES.stream().anyMatch(candidate::startsWith)
                    && !snapshot.allowedSourceRefs().contains(candidate)) {
                throw new AgentContractException(
                        "INVOKE_CAPABILITY argument ref outside the allowed snapshot refs: "
                                + candidate);
            }
        }
    }

    /**
     * 按动作族(family)做 payload 形状校验。payload 在传输层是通用 map;
     * 无论属于哪个 family,凡夹带运行时拥有的身份字段一律拒绝。形状在此
     * 校验;kind/subtype 白名单由运行时在执行时强制。
     */
    private static void validatePayload(ActionFamily family, Map<String, Object> payload,
                                         AgentInputSnapshot snapshot) {
        rejectRuntimeOwnedKeys("payload", payload);
        switch (family) {
            case REQUEST_USER_INPUT -> validateRequestUserInput(payload);
            case CREATE_NODE -> validateCreateNode(payload);
            case CONNECT_NODE -> validateConnectNode(payload, snapshot);
            case INVOKE_CAPABILITY -> validateInvokeCapability(payload, snapshot);
            case UPDATE_NODE, CREATE_ROUTE, RESPOND_TO_USER,
                 GENERATE_ARTIFACT, WAIT -> {
                // 无形状要求的 family:只适用通用的身份字段规则。
                // RESPOND_TO_USER 的 message 是否存在由执行器(executor)检查。
            }
        }
    }

    private static final Set<String> NODE_KINDS = Set.of(
            "KNOWLEDGE", "INTERACTION", "RESOURCE", "ARTIFACT");

    /**
     * CREATE_NODE 的 payload:要么是交互式提问(questionText + options +
     * allowFreeAnswer,kind 默认为 INTERACTION/QUESTION),要么是非交互的
     * 工作区单元(kind + subtype + content.text)。
     */
    @SuppressWarnings("unchecked")
    private static void validateCreateNode(Map<String, Object> payload) {
        Object kindValue = payload.get("kind");
        String kind = kindValue == null ? "INTERACTION" : asString(kindValue, "kind");
        requireEnum("node kind", kind, NODE_KINDS);
        if ("INTERACTION".equals(kind)) {
            validateRequestUserInput(payload);
            return;
        }
        requireNonBlank("subtype", asString(payload.get("subtype"), "subtype"));
        Object content = payload.get("content");
        if (!(content instanceof Map<?, ?> contentMap)) {
            throw new AgentContractException("CREATE_NODE payload requires a content object");
        }
        rejectRuntimeOwnedKeys("content", (Map<String, Object>) contentMap);
        requireNonBlank("content.text",
                asString(contentMap.get("text"), "content.text"));
    }

    private static final Set<String> RELATION_CLASSES = Set.of("CONTINUATION", "SEMANTIC");
    private static final Set<String> SEMANTIC_RELATION_TYPES = Set.of(
            "RELATED_TO", "DEPENDS_ON", "DERIVED_FROM", "CONFLICTS_WITH", "SUPPORTS");

    /**
     * CONNECT_NODE 的 payload:关系类别(relation class)必须显式给出。
     * 语义关系的端点用允许的 {@code node:} ref 表示;所有节点身份都由
     * 运行时拥有。
     */
    private static void validateConnectNode(Map<String, Object> payload,
                                            AgentInputSnapshot snapshot) {
        requireEnum("relationClass",
                asString(payload.get("relationClass"), "relationClass"), RELATION_CLASSES);
        requireEnum("relationType",
                asString(payload.get("relationType"), "relationType"), SEMANTIC_RELATION_TYPES);
        String sourceRef = asString(payload.get("sourceRef"), "sourceRef");
        String targetRef = asString(payload.get("targetRef"), "targetRef");
        if (!sourceRef.startsWith("node:") || !targetRef.startsWith("node:")) {
            throw new AgentContractException(
                    "CONNECT_NODE endpoints must be node: refs from the snapshot");
        }
        if (!snapshot.allowedSourceRefs().contains(sourceRef)
                || !snapshot.allowedSourceRefs().contains(targetRef)) {
            throw new AgentContractException(
                    "CONNECT_NODE endpoint refs outside the allowed snapshot refs");
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateRequestUserInput(Map<String, Object> payload) {
        requireNonBlank("questionText", asString(payload.get("questionText"), "questionText"));
        Object options = payload.get("options");
        if (!(options instanceof List<?> optionList)) {
            throw new AgentContractException("REQUEST_USER_INPUT payload requires an options array");
        }
        for (Object option : optionList) {
            if (!(option instanceof Map<?, ?> optionMap)) {
                throw new AgentContractException("Each option must be an object with a label");
            }
            rejectRuntimeOwnedKeys("option", (Map<String, Object>) optionMap);
            requireNonBlank("option label", asString(optionMap.get("label"), "option.label"));
        }
        if (!(payload.get("allowFreeAnswer") instanceof Boolean)) {
            throw new AgentContractException(
                    "REQUEST_USER_INPUT payload requires boolean allowFreeAnswer");
        }
    }

    private static void rejectRuntimeOwnedKeys(String container, Map<String, Object> map) {
        for (String key : map.keySet()) {
            if (FORBIDDEN_PAYLOAD_KEYS.contains(key)) {
                throw new AgentContractException(
                        "Proposal " + container + " must not carry runtime-owned field: " + key);
            }
        }
    }

    private static void validateSourceRefs(List<String> refs, AgentInputSnapshot snapshot) {
        if (refs.size() > MAX_SOURCE_REFS) {
            throw new AgentContractException("Too many source refs: " + refs.size());
        }
        for (String ref : refs) {
            if (!snapshot.allowedSourceRefs().contains(ref)) {
                throw new AgentContractException(
                        "Source reference outside the allowed snapshot refs: " + ref);
            }
        }
    }

    private static void requireEnum(String name, String value, Set<String> allowed) {
        requireNonBlank(name, value);
        if (!allowed.contains(value)) {
            throw new AgentContractException("Unknown " + name + ": " + value);
        }
    }

    private static void requireNonBlank(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new AgentContractException(name + " must be a non-blank string");
        }
    }

    private static String asString(Object value, String name) {
        if (value instanceof String text) {
            return text;
        }
        throw new AgentContractException(name + " must be a string");
    }
}

package com.specagent.agent.decision;

import com.specagent.agent.contract.ActionFamily;
import com.specagent.agent.contract.AgentInputSnapshot;
import com.specagent.agent.contract.AgentContractException;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.AgentResponseEnvelope;
import com.specagent.agent.contract.ClaimVocabulary;
import com.specagent.agent.contract.ProposedClaim;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Fail-closed validation of every brain response before any persistence or
 * execution. The brain is untrusted input: a response that invents runtime
 * identity, references sources outside the frozen snapshot, echoes a stale
 * base context, exceeds its call budget, or carries an unknown action family
 * is rejected as a whole.
 *
 * <p>Pure contract logic: no repositories, no gateway, no persistence.
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

    /** Validates a {@code POST /v1/state-updates} response against its request. */
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

    /** Validates a {@code POST /v1/decisions} response against its request. */
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

    private static void validateObservation(com.specagent.agent.contract.ObservationView observation) {
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
                                         com.specagent.agent.contract.ActionProposal proposal) {
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

    private static final java.util.List<String> REF_PREFIXES =
            java.util.List.of("node:", "answer:", "patch:", "context:", "route:");

    /**
     * INVOKE_CAPABILITY payload: a non-blank capabilityId plus an optional
     * arguments object. Any argument value shaped like a runtime ref must be
     * inside the snapshot's allowed refs — a generic anti-smuggling rule,
     * not tied to any particular argument name. Unknown capability ids are
     * denied by policy (fail-closed at execution); the validator checks wire
     * shape only.
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
     * Family-specific payload shape checks. Payloads are generic maps on the
     * wire; anything that smuggles runtime-owned identity fields is rejected
     * regardless of family. Shape is validated here; kind/subtype whitelists
     * are enforced by the runtime at execution time.
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
                // Shape-free families: only the generic identity rules apply.
                // RESPOND_TO_USER message presence is checked by the executor.
            }
        }
    }

    private static final Set<String> NODE_KINDS = Set.of(
            "KNOWLEDGE", "INTERACTION", "RESOURCE", "ARTIFACT");

    /**
     * CREATE_NODE payload: either an interaction question (questionText +
     * options + allowFreeAnswer, kind defaults to INTERACTION/QUESTION) or a
     * non-interaction workspace unit (kind + subtype + content.text).
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
     * CONNECT_NODE payload: relation class is explicit. Semantic relations
     * name their endpoints as allowed {@code node:} refs; the runtime owns
     * all node identity.
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

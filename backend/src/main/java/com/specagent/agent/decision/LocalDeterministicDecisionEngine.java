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
 * Deterministic in-JVM decision engine selected only by the explicit
 * {@code spec.agent.brain.engine=fake} configuration. It produces exactly the
 * canonical fake outputs shared with the Python brain's fake model client
 * (see {@code contracts/fixtures/fake-model-*.json}) and passes them through
 * the same fail-closed validator as the remote engine, so tests exercise the
 * identical contract path without HTTP.
 *
 * <p>Normal product configuration never selects this engine.
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
        // Declared test-only failure hook: inert unless the submitted answer
        // text carries a directive (see DeterministicEngineFaultPlan).
        faultPlan.failStateUpdateIfDirected(request);
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
        UUID snapshotId = UUID.fromString(request.snapshot().snapshotId());
        if ("NODE_QUERY".equals(request.event().kind())) {
            // Deterministic E2E mutation path: an explicit "建立语义关联"
            // instruction yields a confirmable CONNECT_NODE proposal between
            // the first two lineage nodes. Every other query input keeps the
            // read-only response below — the query contract otherwise stays
            // side-effect free.
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
            // Deterministic contextual answer: the query path expects a
            // read-only response and must never mutate the graph.
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
        // Deterministic capability path: choose a capability whose declared
        // context support matches the projected lineage. Workspace-level
        // retrieval capabilities intentionally have no node-kind support and
        // require a real query argument, so they are not guessed by this
        // fixture engine for an ordinary answer cycle.
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
        // A CONTINUE event carrying free text is a directed revision (e.g.
        // replacement): the deterministic proposal reflects the direction with
        // a distinct question instead of the canonical draft question.
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
            // Deterministic clarification ladder: the fake must never repeat
            // an already-answered question, or the enforced RESOLVED_BLOCKER
            // rule fails the run before it reaches terminal. Zero answered
            // questions keep the canonical first question; each answered one
            // advances to the next rung, and the fallback stays clear of every
            // answered text under the same normalization the gate enforces.
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
        // Runtime capability descriptors also use the compact project shape:
        // {"query": {"type":"string", "required":true}}. Keep the
        // fake engine generic so it never invents a nodeRef for a required
        // argument it cannot safely synthesize.
        Object queryDefinition = schema.get("query");
        if (queryDefinition instanceof Map<?, ?> definition) {
            Object required = definition.get("required");
            return Boolean.TRUE.equals(required) || "true".equalsIgnoreCase(String.valueOf(required));
        }
        return false;
    }

    /**
     * Deterministic clarification ladder shared with the Python brain's fake
     * model client: picks the first candidate whose normalized text is not an
     * already-answered lineage question. The first rung is the canonical fake
     * question so zero-answered behavior is unchanged; the numbered fallback
     * keeps advancing past any answered text when every named rung is taken.
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

    /** One rung of the deterministic clarification ladder. */
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
        String contextRef = request.snapshot().allowedSourceRefs().stream()
                .filter(ref -> ref.startsWith("context:"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Artifact generation requires a context ref in the snapshot"));
        // Deterministic fake output shared with the Python brain's fake model
        // client (contracts/fixtures/fake-model-artifact-output.json); every
        // section cites the trusted snapshot's own context ref.
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

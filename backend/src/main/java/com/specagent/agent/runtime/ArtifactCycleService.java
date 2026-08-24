package com.specagent.agent.runtime;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunFailureService;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.ModelContractException;
import com.specagent.agent.contract.AgentArtifactResponse;
import com.specagent.agent.contract.AgentEvent;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.DecisionBudget;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.gates.ContextGuard;
import com.specagent.agent.contracts.ReflectionResult;
import com.specagent.agent.contracts.SpecDraft;
import com.specagent.agent.gates.SpecGroundingGate;
import com.specagent.agent.gates.SpecSourceReferenceGuard;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextOperationType;
import com.specagent.context.ContextSnapshot;
import com.specagent.project.ProjectRepository;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import com.specagent.spec.SourceKind;
import com.specagent.spec.SourceReference;
import com.specagent.spec.SpecSection;
import com.specagent.spec.SpecSnapshot;
import com.specagent.spec.SpecSnapshotService;
import com.specagent.spec.UnresolvedItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Artifact generation cycle: exactly 1 ARTIFACT_GENERATION call producing a
 * derived, read-only spec snapshot. There is no graph mutation and no answer
 * to interpret, so there is no STATE_UPDATE and no policy chain — but the
 * same fail-closed grounding semantics as the legacy loop
 * ({@link SpecGroundingGate} + {@link SpecSourceReferenceGuard}) run before
 * anything persists, and the snapshot carries its run provenance.
 */
@Service
public class ArtifactCycleService {

    private static final Logger LOG = LoggerFactory.getLogger(ArtifactCycleService.class);

    private final AgentRunService agentRunService;
    private final AgentRunFailureService agentRunFailureService;
    private final ContextBuilder contextBuilder;
    private final ContextGuard contextGuard;
    private final AgentInputSnapshotBuilder snapshotBuilder;
    private final AgentDecisionEngine decisionEngine;
    private final SpecGroundingGate specGroundingGate;
    private final SpecSourceReferenceGuard specSourceReferenceGuard;
    private final SpecSnapshotService specSnapshotService;
    private final AgentRunEventService eventService;
    private final RouteRepository routeRepository;
    private final com.specagent.project.ProjectRepository projectRepository;

    public ArtifactCycleService(AgentRunService agentRunService,
                                AgentRunFailureService agentRunFailureService,
                                ContextBuilder contextBuilder,
                                ContextGuard contextGuard,
                                AgentInputSnapshotBuilder snapshotBuilder,
                                AgentDecisionEngine decisionEngine,
                                SpecGroundingGate specGroundingGate,
                                SpecSourceReferenceGuard specSourceReferenceGuard,
                                SpecSnapshotService specSnapshotService,
                                AgentRunEventService eventService,
                                RouteRepository routeRepository,
                                com.specagent.project.ProjectRepository projectRepository) {
        this.agentRunService = agentRunService;
        this.agentRunFailureService = agentRunFailureService;
        this.contextBuilder = contextBuilder;
        this.contextGuard = contextGuard;
        this.snapshotBuilder = snapshotBuilder;
        this.decisionEngine = decisionEngine;
        this.specGroundingGate = specGroundingGate;
        this.specSourceReferenceGuard = specSourceReferenceGuard;
        this.specSnapshotService = specSnapshotService;
        this.eventService = eventService;
        this.routeRepository = routeRepository;
        this.projectRepository = projectRepository;
    }

    /**
     * Executes one spec-snapshot generation run against the run's own frozen
     * target (route + input node). The context snapshot is built for exactly
     * {@code run.routeId} / {@code run.inputNodeId}; the project's active
     * route pointer is never consulted to choose a context or persistence
     * target, so a queued run cannot contaminate its snapshot with another
     * route.
     *
     * <p>Live-state validation before any model call: the run's route must
     * still exist, belong to the run's project, be OPEN, and still be the
     * project's ACTIVE route with {@code run.inputNodeId} as its tip. If the
     * user switched the active route while this run was queued, the run fails
     * closed (STALE) instead of generating a mixed or outdated spec.
     */
    public SpecGenerationOutcome generateSpec(AgentRun run) {
        Route route = routeRepository.findById(run.routeId())
                .orElseThrow(() -> new IllegalStateException(
                        "Route not found: " + run.routeId()));
        if (!route.projectId().equals(run.projectId())) {
            throw new StaleRunTargetException(
                    "Run route does not belong to the run's project: " + run.routeId());
        }
        if (route.tipNodeId() == null) {
            throw new StaleRunTargetException(
                    "Run target route has no tip node: " + route.id());
        }
        if (!route.lifecycleStatus().equals(com.specagent.route.RouteLifecycleStatus.OPEN)) {
            throw new StaleRunTargetException(
                    "Run target route is no longer OPEN: " + route.id());
        }

        com.specagent.project.Project project = projectRepository.findById(run.projectId())
                .orElseThrow(() -> new IllegalStateException(
                        "Project not found: " + run.projectId()));
        if (!java.util.Objects.equals(project.activeRouteId(), run.routeId())) {
            throw new StaleRunTargetException(
                    "Active route changed while artifact run was queued: run route "
                            + run.routeId() + ", active route " + project.activeRouteId());
        }
        if (!java.util.Objects.equals(run.inputNodeId(), route.tipNodeId())) {
            throw new StaleRunTargetException(
                    "Run input node is no longer the target route tip: " + run.inputNodeId()
                            + " (current tip: " + route.tipNodeId() + ")");
        }

        String trace = "created";
        try {
            trace = appendTrace(trace, "context_built");
            // Route-bound build: never re-reads the active route pointer.
            ContextSnapshot snapshot = contextBuilder.buildForRoute(
                    run.projectId(), run.routeId(), run.inputNodeId(),
                    run.id(), ContextOperationType.NORMAL);
            agentRunService.attachContext(run.id(), snapshot.id(), trace);
            eventService.append(run.id(), AgentRunPhase.SNAPSHOT_BUILT, "SNAPSHOT_BUILT", Map.of(
                    "snapshotId", snapshot.id().toString(),
                    "contextHash", snapshot.contextHash()));

            if (!contextGuard.validate(snapshot).accepted()) {
                throw new ModelContractException("Context guard rejected agent run");
            }

            // Pure derivation: one artifact call, never a STATE_UPDATE.
            AgentRequestEnvelope envelope = snapshotBuilder.buildEnvelope(
                    run.id(), snapshot,
                    new AgentEvent("CONTINUE", route.tipNodeId(), null, null),
                    new DecisionBudget(1));

            trace = appendTrace(trace, "artifact_generating");
            eventService.append(run.id(), AgentRunPhase.ARTIFACT_GENERATING,
                    "ARTIFACT_GENERATION_STARTED", Map.of());
            AgentArtifactResponse response = decisionEngine.runArtifactGeneration(envelope);
            AgentArtifactResponse.ArtifactGenerationResult result = response.artifact();

            // Grounding gates preserved from the legacy loop, in the same
            // order and with the same failure semantics.
            SpecDraft draft = toSpecDraft(result);
            ReflectionResult grounding = specGroundingGate.validate(draft);
            trace = appendTrace(trace, "reflected:SPEC_GROUNDING");
            agentRunService.markReflected(run.id(), trace);
            if (!grounding.accepted()) {
                agentRunService.fail(run.id(),
                        appendTrace(trace, "failed:spec_grounding_rejected"));
                throw new ModelContractException("Spec grounding rejected spec draft");
            }

            List<SourceReference> sourceRefs = distinctSourceRefs(result);
            ReflectionResult sourceRefsReflection = specSourceReferenceGuard.validate(
                    run.projectId(), route.id(), snapshot, sourceRefs);
            trace = appendTrace(trace, "reflected:SOURCE_REFERENCES");
            agentRunService.markReflected(run.id(), trace);
            if (!sourceRefsReflection.accepted()) {
                agentRunService.fail(run.id(),
                        appendTrace(trace, "failed:source_references_rejected"));
                throw new ModelContractException(
                        "Spec source reference guard rejected spec draft");
            }

            List<SpecSection> sections = result.sections().stream()
                    .map(section -> SpecSection.of(section.title(), section.content()))
                    .toList();
            List<UnresolvedItem> unresolvedItems = result.unresolvedItems().stream()
                    .map(text -> UnresolvedItem.of(text, "unresolved"))
                    .toList();

            // Persistence invariant guard: run target == context == spec
            // identity must hold right before anything is written.
            if (!snapshot.projectId().equals(run.projectId())
                    || !snapshot.routeId().equals(route.id())
                    || !snapshot.tipNodeId().equals(route.tipNodeId())
                    || !snapshot.routeId().equals(run.routeId())
                    || !snapshot.tipNodeId().equals(run.inputNodeId())) {
                throw new ModelContractException(
                        "Artifact persistence invariant violated: run/context/spec targets diverged");
            }
            SpecSnapshot persisted = specSnapshotService.createSnapshot(
                    run.projectId(), route.id(), route.tipNodeId(), snapshot.id(),
                    "markdown", sections, unresolvedItems, sourceRefs, run.id());
            trace = appendTrace(trace, "persisted_spec_snapshot");
            agentRunService.markPersistedSpecSnapshot(run.id(), persisted.id(), trace);
            trace = appendTrace(trace, "completed");
            agentRunService.complete(run.id(), AgentRunStatus.COMPLETED, trace);
            eventService.append(run.id(), AgentRunPhase.COMPLETED, "RUN_COMPLETED",
                    Map.of("producedSpecSnapshotId", persisted.id().toString()));

            return new SpecGenerationOutcome(run.id(), persisted.id());
        } catch (RuntimeException ex) {
            failIfNotTerminal(run.id(), trace, ex);
            throw ex;
        }
    }

    /** Runtime-owned source-ref parsing; the model never invents ids. */
    private List<SourceReference> distinctSourceRefs(
            AgentArtifactResponse.ArtifactGenerationResult result) {
        Set<String> refs = new LinkedHashSet<>();
        for (AgentArtifactResponse.ArtifactSection section : result.sections()) {
            refs.addAll(section.sourceRefs());
        }
        List<SourceReference> parsed = new ArrayList<>();
        for (String ref : refs) {
            int separator = ref.indexOf(':');
            if (separator <= 0 || separator == ref.length() - 1) {
                throw new ModelContractException(
                        "Spec source reference must be kind:uuid: " + ref);
            }
            parsed.add(SourceReference.of(
                    SourceKind.fromCode(ref.substring(0, separator)),
                    UUID.fromString(ref.substring(separator + 1))));
        }
        return parsed;
    }

    private SpecDraft toSpecDraft(AgentArtifactResponse.ArtifactGenerationResult result) {
        Map<String, String> sections = new LinkedHashMap<>();
        Map<String, List<String>> refsBySection = new LinkedHashMap<>();
        for (AgentArtifactResponse.ArtifactSection section : result.sections()) {
            sections.put(section.title(), section.content());
            refsBySection.put(section.title(),
                    section.sourceRefs() == null ? List.of() : section.sourceRefs());
        }
        return new SpecDraft(sections, result.unresolvedItems(), refsBySection);
    }

    private void failIfNotTerminal(UUID runId, String trace, RuntimeException ex) {
        LOG.warn("Agent run {} failed at {}: {}", runId, trace, ex.getMessage());
        AgentRun latest = agentRunService.getRun(runId).orElse(null);
        if (latest != null && latest.status() != AgentRunStatus.FAILED
                && latest.status() != AgentRunStatus.COMPLETED) {
            agentRunFailureService.fail(runId, appendTrace(trace, "failed"));
            eventService.append(runId, AgentRunPhase.FAILED, "RUN_FAILED",
                    Map.of("reason", ex.getClass().getSimpleName()));
        }
    }

    private String appendTrace(String trace, String step) {
        return trace + ">" + step;
    }

    /** Post-run view over one spec generation cycle. */
    public record SpecGenerationOutcome(UUID runId, UUID producedSpecSnapshotId) {
    }
}

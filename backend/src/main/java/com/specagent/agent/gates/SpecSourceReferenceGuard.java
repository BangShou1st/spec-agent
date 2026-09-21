package com.specagent.agent.gates;

import com.specagent.agent.contracts.ReflectionResult;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerRepository;
import com.specagent.context.ContextSnapshot;
import com.specagent.node.Node;
import com.specagent.node.NodeRepository;
import com.specagent.patch.AnswerPatch;
import com.specagent.patch.AnswerPatchRepository;
import com.specagent.route.Route;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteRepository;
import com.specagent.route.RouteHistoryResolver;
import com.specagent.spec.SourceKind;
import com.specagent.spec.SourceReference;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Deterministic guard that verifies every spec source reference points at a
 * real runtime record belonging to the current project, route, and context
 * snapshot.
 *
 * <p>This runs after {@link SpecGroundingGate} and before a spec snapshot is
 * persisted. It never replaces grounding; it hardens it: a grounded section
 * may only cite records the frozen context actually included.
 */
@Component
public class SpecSourceReferenceGuard {

    private final RouteRepository routeRepository;
    private final NodeRepository nodeRepository;
    private final AnswerRepository answerRepository;
    private final AnswerPatchRepository answerPatchRepository;
    private final RouteHistoryResolver routeHistoryResolver;

    public SpecSourceReferenceGuard(RouteRepository routeRepository,
                                    NodeRepository nodeRepository,
                                    AnswerRepository answerRepository,
                                    AnswerPatchRepository answerPatchRepository,
                                    RouteHistoryResolver routeHistoryResolver) {
        this.routeRepository = routeRepository;
        this.nodeRepository = nodeRepository;
        this.answerRepository = answerRepository;
        this.answerPatchRepository = answerPatchRepository;
        this.routeHistoryResolver = routeHistoryResolver;
    }

    public ReflectionResult validate(UUID projectId,
                                     UUID routeId,
                                     ContextSnapshot contextSnapshot,
                                     List<SourceReference> sourceRefs) {
        List<String> errors = new ArrayList<>();

        if (sourceRefs == null || sourceRefs.isEmpty()) {
            return ReflectionResult.rejectedResult("Spec source references are required");
        }

        // Whole-context invariant first: even when every individual ref is
        // real and in-context, a caller that pairs route A with a snapshot of
        // route B must be rejected before per-ref validation can pass.
        if (contextSnapshot == null) {
            errors.add("Context snapshot is required");
        } else {
            if (!contextSnapshot.projectId().equals(projectId)) {
                errors.add("Context snapshot does not belong to project " + projectId);
            }
            if (!contextSnapshot.routeId().equals(routeId)) {
                errors.add("Context snapshot does not belong to route " + routeId);
            }
        }

        // Answers inherited from the route this one branched off are part of
        // THIS route's effective history (see {@code route_inherited_answers})
        // and were frozen into the snapshot by the ContextBuilder, so the
        // branch route may legitimately cite them. Ancestor answers the
        // snapshot did NOT include stay rejected.
        Set<UUID> citableInheritedAnswers = contextSnapshot == null ? Set.of()
                : routeHistoryResolver.resolveEffectiveAnswers(routeId,
                        contextSnapshot.includedNodeIds()).stream()
                        .map(Answer::id)
                        .collect(Collectors.toSet());
        Set<UUID> citableInheritedPatches = citableInheritedAnswers.isEmpty() ? Set.of()
                : answerPatchRepository.findBySourceAnswerIds(citableInheritedAnswers.stream().toList())
                        .stream().map(AnswerPatch::id)
                        .collect(Collectors.toSet());

        for (SourceReference ref : sourceRefs) {
            validateRef(projectId, routeId, contextSnapshot, ref, errors,
                    citableInheritedAnswers, citableInheritedPatches);
        }

        if (errors.isEmpty()) {
            return ReflectionResult.acceptedResult();
        }
        return new ReflectionResult(false, errors, List.of());
    }

    private void validateRef(UUID projectId,
                             UUID routeId,
                             ContextSnapshot contextSnapshot,
                             SourceReference ref,
                             List<String> errors,
                             Set<UUID> citableInheritedAnswers,
                             Set<UUID> citableInheritedPatches) {
        if (contextSnapshot == null) {
            return;
        }
        switch (ref.kind()) {
            case CONTEXT -> {
                if (!ref.refId().equals(contextSnapshot.id())) {
                    errors.add("Context source reference must match the run context snapshot: " + ref.refId());
                }
                if (!contextSnapshot.projectId().equals(projectId)) {
                    errors.add("Context snapshot does not belong to project " + projectId);
                }
                if (!contextSnapshot.routeId().equals(routeId)) {
                    errors.add("Context snapshot does not belong to route " + routeId);
                }
            }
            case ROUTE -> {
                Route route = routeRepository.findById(ref.refId()).orElse(null);
                if (route == null) {
                    errors.add("Route source reference does not exist: " + ref.refId());
                } else {
                    if (!route.projectId().equals(projectId)) {
                        errors.add("Route source reference does not belong to project " + projectId);
                    }
                    if (!route.id().equals(routeId)) {
                        errors.add("Route source reference is not the current route: " + ref.refId());
                    }
                    if (route.lifecycleStatus() != RouteLifecycleStatus.OPEN) {
                        errors.add("Route source reference is not OPEN: " + ref.refId());
                    }
                }
            }
            case NODE -> {
                Node node = nodeRepository.findById(ref.refId()).orElse(null);
                if (node == null) {
                    errors.add("Node source reference does not exist: " + ref.refId());
                } else {
                    if (!node.projectId().equals(projectId)) {
                        errors.add("Node source reference does not belong to project " + projectId);
                    }
                    if (!contextSnapshot.includedNodeIds().contains(ref.refId())) {
                        errors.add("Node source reference is not in the run context: " + ref.refId());
                    }
                }
            }
            case ANSWER -> {
                Answer answer = answerRepository.findById(ref.refId()).orElse(null);
                if (answer == null) {
                    errors.add("Answer source reference does not exist: " + ref.refId());
                } else {
                    if (!answer.projectId().equals(projectId)) {
                        errors.add("Answer source reference does not belong to project " + projectId);
                    }
                    // Route-local answers must belong to the current route;
                    // inherited answers must be part of this route's effective
                    // (branched-off) history. Both must be in the frozen
                    // context, so a sibling or foreign route can never be cited.
                    if (!answer.routeId().equals(routeId)
                            && !citableInheritedAnswers.contains(ref.refId())) {
                        errors.add("Answer source reference does not belong to route " + routeId);
                    }
                    if (!contextSnapshot.includedAnswerIds().contains(ref.refId())) {
                        errors.add("Answer source reference is not in the run context: " + ref.refId());
                    }
                }
            }
            case PATCH -> {
                AnswerPatch patch = answerPatchRepository.findById(ref.refId()).orElse(null);
                if (patch == null) {
                    errors.add("Patch source reference does not exist: " + ref.refId());
                } else {
                    if (!patch.projectId().equals(projectId)) {
                        errors.add("Patch source reference does not belong to project " + projectId);
                    }
                    if (!patch.routeId().equals(routeId)
                            && !citableInheritedPatches.contains(ref.refId())) {
                        errors.add("Patch source reference does not belong to route " + routeId);
                    }
                    if (!contextSnapshot.includedPatchIds().contains(ref.refId())) {
                        errors.add("Patch source reference is not in the run context: " + ref.refId());
                    }
                }
            }
            default -> errors.add("Unsupported source reference kind: " + ref.kind());
        }
    }
}

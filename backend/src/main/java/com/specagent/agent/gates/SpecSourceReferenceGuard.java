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
import com.specagent.spec.SourceKind;
import com.specagent.spec.SourceReference;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    public SpecSourceReferenceGuard(RouteRepository routeRepository,
                                    NodeRepository nodeRepository,
                                    AnswerRepository answerRepository,
                                    AnswerPatchRepository answerPatchRepository) {
        this.routeRepository = routeRepository;
        this.nodeRepository = nodeRepository;
        this.answerRepository = answerRepository;
        this.answerPatchRepository = answerPatchRepository;
    }

    public ReflectionResult validate(UUID projectId,
                                     UUID routeId,
                                     ContextSnapshot contextSnapshot,
                                     List<SourceReference> sourceRefs) {
        List<String> errors = new ArrayList<>();

        if (sourceRefs == null || sourceRefs.isEmpty()) {
            return ReflectionResult.rejectedResult("Spec source references are required");
        }

        for (SourceReference ref : sourceRefs) {
            validateRef(projectId, routeId, contextSnapshot, ref, errors);
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
                             List<String> errors) {
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
                    if (!answer.routeId().equals(routeId)) {
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
                    if (!patch.routeId().equals(routeId)) {
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

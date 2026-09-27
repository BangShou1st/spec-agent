package com.specagent.agent.gates;

import com.specagent.agent.decision.ReflectionResult;
import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerRepository;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeRepository;
import com.specagent.workspace.patch.AnswerPatch;
import com.specagent.workspace.patch.AnswerPatchRepository;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteLifecycleStatus;
import com.specagent.workspace.route.RouteRepository;
import com.specagent.workspace.route.RouteHistoryResolver;
import com.specagent.workspace.spec.SourceKind;
import com.specagent.workspace.spec.SourceReference;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 文件名:SpecSourceReferenceGuard.java
 *
 * 用途:确定性门禁,校验每条 spec 来源引用都指向真实存在的 Runtime
 * 记录,且属于当前项目、当前路由和当前上下文快照。
 *
 * 运行在 {@link SpecGroundingGate} 之后、spec 快照持久化之前。
 * 它不替代 grounding,而是加固:已 grounded 的分节只能引用冻结上下文
 * 实际包含的记录。
 *
 * 协作:由 spec 生成流程调用,拒绝时返回带错误列表的 ReflectionResult。
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

        // 先做整体上下文不变量:即使每条 ref 都是真实且在上下文内的,
        // "路由 A 搭配路由 B 的快照"的调用也必须在逐条校验之前被拒绝。
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

        // 从本路由分叉出去的那条路由所继承的 Answer 属于本路由的
        // 有效历史(见 {@code route_inherited_answers}),并已由
        // ContextBuilder 冻结进快照,因此分支路由可以合法引用它们。
        // 快照未包含的祖先 Answer 仍然被拒绝。
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
                    // 路由本地的 Answer 必须属于当前路由;继承的 Answer 必须属于
                    // 本路由的有效(分叉)历史。两者都必须在冻结上下文内,
                    // 因此兄弟路由或外部路由的 Answer 永远不可能被引用。
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

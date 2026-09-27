package com.specagent.agent.gates;

import com.specagent.agent.decision.ReflectionResult;
import com.specagent.workspace.context.ContextOperationType;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectRepository;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteLifecycleStatus;
import com.specagent.workspace.route.RouteRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件名:ContextGuard.java
 *
 * 用途:任何 agent 步骤执行之前,对上下文快照做确定性校验的门禁。
 *
 * 上下文只有在其路由存在、属于上下文所在项目且处于 OPEN 状态
 * (显式替换探索时可为 SUPERSEDED)时才可使用。普通(非 regenerate)
 * 上下文还必须匹配项目的活动路由;regenerate 上下文是特殊的操作上下文,
 * 因此豁免活动路由匹配。
 *
 * 无路由的 NODE_QUERY 上下文是一等公民:游离的规范节点
 * (routeIds=[])携带 {@code routeId == null},其快照就是锚点节点自身的
 * 血缘。这类快照必须校验项目与上下文哈希,但绝不要求路由、活动路由或
 * 路由-活动匹配。其他所有操作类型仍保留下述严格的路由要求。
 *
 * 协作:由 run 流水线在构建模型输入前调用,拒绝时返回带错误列表的
 * ReflectionResult。
 */
@Component
public class ContextGuard {

    private final ProjectRepository projectRepository;
    private final RouteRepository routeRepository;

    public ContextGuard(ProjectRepository projectRepository, RouteRepository routeRepository) {
        this.projectRepository = projectRepository;
        this.routeRepository = routeRepository;
    }

    /**
     * 默认校验:上下文路由必须是项目的 Active 路由。
     * 保留单参数入口,使所有既有调用方和整套 active-route 不变量测试
     * 不受影响。
     */
    public ReflectionResult validate(ContextSnapshot snapshot) {
        return validate(snapshot, false);
    }

    /**
     * 校验一个上下文快照。
     *
     * @param explicitRoute 当 run 是针对 EXPLICIT 路由(而非项目 Active 路由)
     *        创建时为 true。多路由工作(在非 Active 路由上作答或起草)只有
     *        在此模式下才合法,且即便如此路由也必须属于该项目并处于 OPEN
     *        状态——被跳过的只有 Active 相等检查,生命周期/归属检查从不
     *        跳过。不传显式路由时行为与之前字节级一致。
     */
    public ReflectionResult validate(ContextSnapshot snapshot, boolean explicitRoute) {
        List<String> errors = new ArrayList<>();

        if (snapshot == null) {
            return ReflectionResult.rejectedResult("Context snapshot is required");
        }

        Project project = projectRepository.findById(snapshot.projectId()).orElse(null);
        if (project == null) {
            errors.add("Context project does not exist: " + snapshot.projectId());
        }

        boolean routelessNodeQuery = snapshot.operationType() == ContextOperationType.NODE_QUERY
                && snapshot.routeId() == null;
        if (routelessNodeQuery) {
            // 游离节点查询:上下文就是锚点节点本身,不引用任何路由。
            // 只要求项目存在与上下文哈希;路由/活动路由要求一概不适用。
            if (snapshot.contextHash() == null || snapshot.contextHash().isBlank()) {
                errors.add("Context hash is required");
            }
            if (errors.isEmpty()) {
                return ReflectionResult.acceptedResult();
            }
            return new ReflectionResult(false, errors, List.of());
        }

        Route route = routeRepository.findById(snapshot.routeId()).orElse(null);
        if (route == null) {
            errors.add("Context route does not exist: " + snapshot.routeId());
        } else {
            if (!route.projectId().equals(snapshot.projectId())) {
                errors.add("Context route does not belong to context project");
            }
            boolean replacementSource = snapshot.operationType() == ContextOperationType.REGENERATE;
            boolean validLifecycle = route.lifecycleStatus() == RouteLifecycleStatus.OPEN
                    || (replacementSource && route.lifecycleStatus() == RouteLifecycleStatus.SUPERSEDED);
            if (!validLifecycle) {
                errors.add(replacementSource
                        ? "Replacement context route must be OPEN or SUPERSEDED"
                        : "Context route must be OPEN");
            }
        }

        if (snapshot.operationType() != ContextOperationType.REGENERATE && !explicitRoute) {
            if (project != null) {
                if (project.activeRouteId() == null) {
                    errors.add("Normal context requires project active route");
                } else if (!project.activeRouteId().equals(snapshot.routeId())) {
                    errors.add("Normal context route must match project active route");
                }
            }
        }

        if (snapshot.contextHash() == null || snapshot.contextHash().isBlank()) {
            errors.add("Context hash is required");
        }

        if (errors.isEmpty()) {
            return ReflectionResult.acceptedResult();
        }
        return new ReflectionResult(false, errors, List.of());
    }
}

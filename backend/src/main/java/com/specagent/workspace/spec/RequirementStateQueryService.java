package com.specagent.workspace.spec;

import com.specagent.workspace.context.RequirementState;
import com.specagent.workspace.context.RequirementStateBuilder;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteService;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 文件名:RequirementStateQueryService.java
 *
 * 用途:薄读模型桥接,为项目活跃 route 派生当前需求状态。这是为首个前端
 * 唯一新增的 UI 支撑读桥:组合既有的运行时读取与 {@link RequirementStateBuilder},
 * 绝不写状态、绝不调用模型、绝不构建或落库 {@code ContextSnapshot},也绝不把
 * RequirementState 当成事实源。它不是第二个运行时内核。
 *
 * 项目没有活跃 route 时,返回安全的空读模型而不是凭空造一个 route。若活跃
 * 指针未能解析到本项目的 route,读取以内部不变量违反的方式 fail-closed,外部
 * 数据永远不会被暴露。
 */
@Service
public class RequirementStateQueryService {

    private final ProjectService projectService;
    private final RouteService routeService;
    private final RequirementStateBuilder requirementStateBuilder;

    public RequirementStateQueryService(ProjectService projectService,
                                        RouteService routeService,
                                        RequirementStateBuilder requirementStateBuilder) {
        this.projectService = projectService;
        this.routeService = routeService;
        this.requirementStateBuilder = requirementStateBuilder;
    }

    /**
     * 为项目显式指定的 route 派生需求状态。任何生命周期状态(open、superseded、
     * archived、deleted)都可读取;不属于本项目的 route 与不存在的 route 在 API
     * 边界不可区分,统一表现为 404 {@code ROUTE_NOT_FOUND}。绝不写状态,绝不
     * 构建或落库 {@code ContextSnapshot}。
     */
    public RequirementStateView getForRoute(UUID projectId, UUID routeId) {
        Project project = projectService.getProject(projectId)
                .orElseThrow(() -> RequirementStateQueryException.of(
                        RequirementStateQueryException.Reason.PROJECT_NOT_FOUND, "Project not found"));

        Route route = routeService.getRoute(routeId)
                .orElseThrow(() -> RequirementStateQueryException.of(
                        RequirementStateQueryException.Reason.ROUTE_NOT_FOUND, "Route not found"));

        if (!route.projectId().equals(project.id())) {
            throw RequirementStateQueryException.of(
                    RequirementStateQueryException.Reason.ROUTE_NOT_FOUND, "Route not found");
        }

        RequirementState state = requirementStateBuilder.buildForRoute(project.id(), route.id());
        return RequirementStateView.from(project.id(), route.id(), state);
    }

    public RequirementStateView getForProject(UUID projectId) {
        Project project = projectService.getProject(projectId)
                .orElseThrow(() -> RequirementStateQueryException.of(
                        RequirementStateQueryException.Reason.PROJECT_NOT_FOUND, "Project not found"));

        if (project.activeRouteId() == null) {
            return RequirementStateView.empty(project.id());
        }

        Route activeRoute = routeService.getRoute(project.activeRouteId())
                .orElseThrow(() -> RequirementStateQueryException.of(
                        RequirementStateQueryException.Reason.INVARIANT_VIOLATION,
                        "The active route pointer does not resolve"));
        // 防御性 fail-closed 守卫:在正确的运行时不变量下,活跃指针总是解析到
        // 本项目拥有的 route。一旦不满足,外来 route 及其 claims 都不得暴露,
        // 读取以内部不变量违反的方式失败。
        if (!activeRoute.projectId().equals(project.id())) {
            throw RequirementStateQueryException.of(
                    RequirementStateQueryException.Reason.INVARIANT_VIOLATION,
                    "The active route does not belong to the project");
        }

        RequirementState state = requirementStateBuilder.buildForRoute(project.id(), activeRoute.id());
        return RequirementStateView.from(project.id(), activeRoute.id(), state);
    }
}
package com.specagent.api.common;

import com.specagent.answer.Answer;
import com.specagent.answer.AnswerService;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteService;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Execution wrapper for command endpoints.
 *
 * <p>Expected domain/runtime failures from the orchestrator and route service
 * are translated into stable, safe API errors without ever exposing the raw
 * exception message (which may carry ids or internal state):
 *
 * <pre>
 * IllegalStateException    -> 409 CONFLICT       RUNTIME_CONFLICT
 * IllegalArgumentException -> 400 BAD_REQUEST    INVALID_REQUEST
 * </pre>
 *
 * <p>{@link ApiException} and contract/gateway failures propagate untouched so
 * the central handlers can map them with their own precise statuses. The
 * static validation helpers below give command endpoints precise 404/409
 * errors from existing service reads without ever touching repositories.
 */
public final class CommandExecution {

    private CommandExecution() {
    }

    public static <T> T execute(Supplier<T> action) {
        try {
            return action.get();
        } catch (ApiException ex) {
            throw ex;
        } catch (IllegalStateException ex) {
            throw ApiException.conflict("RUNTIME_CONFLICT",
                    "The request conflicts with the current runtime state");
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("INVALID_REQUEST", "The request is invalid");
        }
    }

    public static Project requireProject(ProjectService projectService, UUID projectId) {
        return projectService.getProject(projectId)
                .orElseThrow(() -> ApiException.notFound("PROJECT_NOT_FOUND", "Project not found"));
    }

    public static Route requireRouteInProject(ProjectService projectService,
                                              RouteService routeService,
                                              UUID projectId,
                                              UUID routeId) {
        requireProject(projectService, projectId);
        Route route = routeService.getRoute(routeId)
                .orElseThrow(() -> ApiException.notFound("ROUTE_NOT_FOUND", "Route not found"));
        if (!route.projectId().equals(projectId)) {
            throw ApiException.notFound("ROUTE_NOT_FOUND", "Route not found");
        }
        return route;
    }

    public static Node requireNodeInProject(ProjectService projectService,
                                            NodeService nodeService,
                                            UUID projectId,
                                            UUID nodeId) {
        requireProject(projectService, projectId);
        Node node = nodeService.getNode(nodeId)
                .orElseThrow(() -> ApiException.notFound("NODE_NOT_FOUND", "Node not found"));
        if (!node.projectId().equals(projectId)) {
            throw ApiException.notFound("NODE_NOT_FOUND", "Node not found");
        }
        return node;
    }

    public static Answer requireAnswerInProject(ProjectService projectService,
                                                AnswerService answerService,
                                                UUID projectId,
                                                UUID answerId) {
        requireProject(projectService, projectId);
        Answer answer = answerService.getAnswer(answerId)
                .orElseThrow(() -> ApiException.notFound("ANSWER_NOT_FOUND", "Answer not found"));
        if (!answer.projectId().equals(projectId)) {
            throw ApiException.notFound("ANSWER_NOT_FOUND", "Answer not found");
        }
        return answer;
    }

    /**
     * Resolves the project's active route. A missing active pointer is a
     * request/state conflict (409), not a not-found resource.
     */
    public static Route requireActiveRoute(Project project, RouteService routeService) {
        if (project.activeRouteId() == null) {
            throw ApiException.conflict("NO_ACTIVE_ROUTE", "The project has no active route");
        }
        return routeService.getRoute(project.activeRouteId())
                .orElseThrow(() -> ApiException.notFound("ROUTE_NOT_FOUND", "Route not found"));
    }
}
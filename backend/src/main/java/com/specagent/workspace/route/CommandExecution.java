package com.specagent.workspace.route;

import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.common.ApiException;
import com.specagent.common.PreciseConflictException;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteService;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 文件名:CommandExecution.java
 *
 * 用途:命令端点的统一执行包装器。把编排器与路线服务抛出的预期内
 * 领域/运行时异常,翻译成稳定、安全的 API 错误,并且绝不把原始异常消息
 * (可能携带内部 id 或状态)暴露出去:
 *
 * IllegalStateException    -> 409 CONFLICT       RUNTIME_CONFLICT
 * IllegalArgumentException -> 400 BAD_REQUEST    INVALID_REQUEST
 *
 * {@link ApiException}、所有 {@link PreciseConflictException} 以及
 * 契约/网关类异常原样向外传播,让中央处理器能按各自的精确状态码/错误码
 * 处理,而不是被笼统的 {@code RUNTIME_CONFLICT} 吞掉。对
 * {@code PreciseConflictException} 的 catch 是保证"精确冲突"家族随演进仍然
 * 安全的唯一钩子:新增的精确冲突只需继承该基类(由架构测试强制约束),
 * 不允许在这里按类名逐个添加。下方的静态校验辅助方法让命令端点基于已有的
 * service 读取就能给出精确的 404/409 错误,而不必直接访问 repository。
 *
 * 摆放位置说明:本辅助类组合了多个运行时切片服务
 * ({@code project}、{@code route}、{@code node}、{@code answer}),
 * 因此属于应用层,而不是共享的 {@code common} 内核。如果放进
 * {@code com.specagent.common},会让 {@code common} 反过来依赖这些切片,
 * 而它们本身已经依赖 {@code common}({@code PreciseConflictException}、
 * {@code Ids}),引入四个新的包循环。放在 {@code com.specagent.workspace}
 * 则保证依赖单向:{@code api -> application -> runtime slices}。
 */
public final class CommandExecution {

    private CommandExecution() {
    }

    public static <T> T execute(Supplier<T> action) {
        try {
            return action.get();
        } catch (ApiException ex) {
            throw ex;
        } catch (PreciseConflictException ex) {
            // 携带自身稳定 reason code 的状态冲突
            // (NO_ACTIVE_ROUTE、ROUTE_NOT_OPEN、RELATION_DEPENDENCY_CYCLE、
            // UNANSWERED_QUESTION_HAS_CHILD 等)。由中央 ApiExceptionHandler
            // 统一映射;绝不能被折叠成下面的笼统 RUNTIME_CONFLICT。
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
     * 解析项目的当前活跃路线。活跃指针缺失属于请求/状态冲突(409),
     * 而不是资源不存在(404)。
     */
    public static Route requireActiveRoute(Project project, RouteService routeService) {
        if (project.activeRouteId() == null) {
            throw ApiException.conflict("NO_ACTIVE_ROUTE", "The project has no active route");
        }
        return routeService.getRoute(project.activeRouteId())
                .orElseThrow(() -> ApiException.notFound("ROUTE_NOT_FOUND", "Route not found"));
    }
}

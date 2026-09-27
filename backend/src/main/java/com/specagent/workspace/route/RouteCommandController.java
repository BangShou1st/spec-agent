package com.specagent.workspace.route;

import com.specagent.workspace.route.RouteCommandService;
import com.specagent.workspace.route.RouteMutationResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 文件名:RouteCommandController.java
 *
 * 用途:路线命令 API。命令统一经过 {@link RouteCommandService} 与
 * 既有 {@link com.specagent.workspace.route.RouteService} 执行;控制器本身
 * 绝不直接写数据库状态,也绝不直接改 {@code Project.activeRouteId}。
 * 读取与命令都不会把路线生命周期改成 {@code active}(激活只能走
 * activate 命令)。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class RouteCommandController {

    private final RouteCommandService routeCommandService;

    public RouteCommandController(RouteCommandService routeCommandService) {
        this.routeCommandService = routeCommandService;
    }

    @PostMapping("/routes/{routeId}/activate")
    public RouteMutationResponse activate(@PathVariable UUID projectId,
                                          @PathVariable UUID routeId) {
        return routeCommandService.activate(projectId, routeId);
    }

    @PostMapping("/routes/{routeId}/archive")
    public RouteMutationResponse archive(@PathVariable UUID projectId,
                                         @PathVariable UUID routeId) {
        return routeCommandService.archive(projectId, routeId);
    }

    @PostMapping("/routes/{routeId}/restore")
    public RouteMutationResponse restore(@PathVariable UUID projectId,
                                         @PathVariable UUID routeId) {
        return routeCommandService.restore(projectId, routeId);
    }

    @PostMapping("/routes/{routeId}/delete")
    public RouteMutationResponse delete(@PathVariable UUID projectId,
                                        @PathVariable UUID routeId) {
        return routeCommandService.softDelete(projectId, routeId);
    }

    @PostMapping("/nodes/{nodeId}/fork")
    public RouteMutationResponse fork(@PathVariable UUID projectId,
                                      @PathVariable UUID nodeId,
                                      @Valid @RequestBody ForkRouteRequest request) {
        return routeCommandService.fork(projectId, nodeId, request.sourceRouteId(), request.label());
    }

    @PostMapping("/nodes/{nodeId}/reanswer")
    public RouteMutationResponse reanswer(@PathVariable UUID projectId,
                                          @PathVariable UUID nodeId,
                                          @Valid @RequestBody ReanswerRouteRequest request) {
        return routeCommandService.reanswer(projectId, nodeId,
                request.sourceRouteId(), request.label());
    }

    /** 从一个游离的知识/资源节点启动一条全新的独立路线
     * ("想法继续生成问题"):该节点成为路线的 root+tip,
     * 下一份问题草稿也锚定在此节点上。 */
    @PostMapping("/nodes/{nodeId}/start-route")
    public RouteMutationResponse startRoute(@PathVariable UUID projectId,
                                            @PathVariable UUID nodeId,
                                            @Valid @RequestBody StartRouteFromNodeRequest request) {
        return routeCommandService.startRouteFromNode(projectId, nodeId, request.label());
    }
}

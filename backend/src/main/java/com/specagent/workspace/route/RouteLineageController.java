package com.specagent.workspace.route;

import com.specagent.workspace.route.RouteLineageQueryService;
import com.specagent.workspace.route.RouteLineageView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 文件名:RouteLineageController.java
 *
 * 用途:只读的路线 lineage(历史节点链)API。通过读模型查询边界
 * 暴露一条路线的历史节点链。此端点只读、不依赖 provider、不依赖模型、
 * 不直接碰持久化:仅检查既有路线用于展示。它是 Phase 7.2 新增的唯一
 * 后端功能;从不构建 {@code ContextSnapshot},也从不用于改变运行时语义。
 *
 * 架构边界(API 层依旧绝不依赖 context、model、repository、credential):
 *
 * RouteLineageController
 *         ↓
 * com.specagent.workspace.route.RouteLineageQueryService
 *         ↓
 * ProjectService / RouteService / NodeService
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/routes/{routeId}")
public class RouteLineageController {

    private final RouteLineageQueryService routeLineageQueryService;

    public RouteLineageController(RouteLineageQueryService routeLineageQueryService) {
        this.routeLineageQueryService = routeLineageQueryService;
    }

    @GetMapping("/lineage")
    public RouteLineageView getLineage(@PathVariable UUID projectId,
                                       @PathVariable UUID routeId) {
        return routeLineageQueryService.getForRoute(projectId, routeId);
    }
}

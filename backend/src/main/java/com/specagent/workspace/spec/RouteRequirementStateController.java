package com.specagent.workspace.spec;

import com.specagent.workspace.spec.RequirementStateQueryService;
import com.specagent.workspace.spec.RequirementStateView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 文件名:RouteRequirementStateController.java
 *
 * 用途:按 route 划界的只读需求状态 API。通过读模型查询边界,暴露后端为
 * 显式指定 route(open、superseded、archived 或 deleted)派生的需求状态。该
 * 端点只读:绝不调用模型、绝不写状态,也绝不落库任何回答、补丁、节点、route
 * 或规格。既有的活跃 route 端点({@link RequirementStateController})保持不变。
 *
 * 架构边界(API 层依旧不依赖 context、模型、仓储或凭据):
 *
 * RouteRequirementStateController
 *         ↓
 * com.specagent.workspace.spec.RequirementStateQueryService
 *         ↓
 * ProjectService / RouteService / RequirementStateBuilder
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/routes/{routeId}/requirement-state")
public class RouteRequirementStateController {

    private final RequirementStateQueryService requirementStateQueryService;

    public RouteRequirementStateController(RequirementStateQueryService requirementStateQueryService) {
        this.requirementStateQueryService = requirementStateQueryService;
    }

    @GetMapping
    public RequirementStateView getRequirementState(@PathVariable UUID projectId,
                                                    @PathVariable UUID routeId) {
        return requirementStateQueryService.getForRoute(projectId, routeId);
    }
}

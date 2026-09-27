package com.specagent.workspace.spec;

import com.specagent.workspace.spec.RequirementStateQueryService;
import com.specagent.workspace.spec.RequirementStateView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 文件名:RequirementStateController.java
 *
 * 用途:只读的需求状态 API。通过读模型查询边界,暴露后端为项目活跃 route
 * 派生的需求状态。该端点只读:绝不调用模型,也绝不落库任何回答、补丁、节点、
 * route 或规格。RequirementState 始终是派生的、可缓存的,绝非事实源。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/requirement-state")
public class RequirementStateController {

    private final RequirementStateQueryService requirementStateQueryService;

    public RequirementStateController(RequirementStateQueryService requirementStateQueryService) {
        this.requirementStateQueryService = requirementStateQueryService;
    }

    @GetMapping
    public RequirementStateView getRequirementState(@PathVariable UUID projectId) {
        return requirementStateQueryService.getForProject(projectId);
    }
}
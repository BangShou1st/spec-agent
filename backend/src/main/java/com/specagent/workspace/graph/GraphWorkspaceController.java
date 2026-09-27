package com.specagent.workspace.graph;

import com.specagent.workspace.graph.GraphWorkspaceQueryService;
import com.specagent.workspace.graph.GraphWorkspaceView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 文件名:GraphWorkspaceController.java
 *
 * 用途:图工作区的只读 REST API。通过读模型查询边界暴露规范的项目图。
 * 该端点只读、不依赖 provider、不调用模型、不做持久化:只把既有 runtime
 * 读取拼装成展示数据。它从不构建 {@code ContextSnapshot},也绝不用于
 * 改变 runtime 语义。
 *
 * 架构边界(API 层依然绝不依赖 context、model、repository、credential):
 *
 * GraphWorkspaceController
 *         ↓
 * com.specagent.workspace.graph.GraphWorkspaceQueryService
 *         ↓
 * ProjectService / RouteService / NodeService / AnswerService
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/graph")
public class GraphWorkspaceController {

    private final GraphWorkspaceQueryService queryService;

    public GraphWorkspaceController(GraphWorkspaceQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public GraphWorkspaceView getGraph(@PathVariable UUID projectId) {
        return queryService.getForProject(projectId);
    }
}

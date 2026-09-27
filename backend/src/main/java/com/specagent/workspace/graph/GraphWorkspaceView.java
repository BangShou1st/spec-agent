package com.specagent.workspace.graph;

import java.util.List;
import java.util.UUID;

/**
 * 文件名:GraphWorkspaceView.java
 *
 * 用途:工作区规范的项目图只读视图。所有路线呈现在同一张图上:
 * 节点去重共享,路线专属答案各自独立;语义关系与续写 lineage 分开携带。
 * 这是一个纯展示读取:绝不用于改变 Runtime 语义,从不构建或持久化
 * {@code ContextSnapshot},也绝不暴露 patch、上下文、模型/provider
 * 数据、凭据或 AgentRun 轨迹。
 */
public record GraphWorkspaceView(
        UUID projectId,
        UUID activeRouteId,
        List<GraphWorkspaceRouteView> routes,
        List<GraphWorkspaceNodeView> nodes,
        List<GraphWorkspaceAnswerView> answers,
        List<GraphWorkspaceRelationView> relations) {
}

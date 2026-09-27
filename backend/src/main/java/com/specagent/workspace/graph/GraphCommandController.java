package com.specagent.workspace.graph;

import com.specagent.workspace.graph.GraphCommandService;
import com.specagent.workspace.graph.GraphOperation;
import com.specagent.workspace.graph.NodeRelation;
import com.specagent.workspace.graph.NodeRelationType;
import com.specagent.workspace.graph.UndoRedoService;
import com.specagent.workspace.node.KnowledgeStatus;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeKind;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.graph.GraphWorkspaceRelationView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:GraphCommandController.java
 *
 * 用途:图工作区的"变更类" REST API 入口。所有写操作(建节点、连线、
 * 摘线、续写分支、挂资源、建语义关系、撤销/重做等)都从这里的端点进入,
 * 每个端点最终落到一个事务化的 Runtime 命令(见 {@link GraphCommandService});
 * 本层不调用任何模型。路线上下文始终由请求显式给出——API 绝不会用
 * "回退到活跃/第一条/最新路线"来消解共享节点的歧义。撤销/重做是基于
 * 类型化操作日志的定向补偿,绝不是破坏性重写历史。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class GraphCommandController {

    private final GraphCommandService commandService;
    private final UndoRedoService undoRedoService;
    private final NodeService nodeService;

    public GraphCommandController(GraphCommandService commandService,
                                  UndoRedoService undoRedoService,
                                  NodeService nodeService) {
        this.commandService = commandService;
        this.undoRedoService = undoRedoService;
        this.nodeService = nodeService;
    }

    /** 在空路线上创建第一个(根)草稿节点。不调用任何模型。 */
    @PostMapping("/nodes")
    public ResponseEntity<NodeResponse> createRootDraftNode(@PathVariable UUID projectId,
                                                             @RequestBody CreateDraftNodeRequest request) {
        Node node = com.specagent.workspace.route.CommandExecution.execute(() -> commandService.createRootDraftNode(
                projectId, request.routeId(), request.subtype(), request.content()));
        return ResponseEntity.status(HttpStatus.CREATED).body(NodeResponse.from(node, request.routeId(), false));
    }

    /**
     * 创建一个独立的(悬浮)草稿,初始不挂在任何 lineage 上,
     * 由用户在画布上手动连线。响应里 {@code routeId = null},
     * 客户端永远不会把悬浮节点当成属于某条路线。创建时所在路线的
     * routeId 只作为创建上下文记入操作日志,不作为归属。
     *
     * {@code kind} 可选,缺省为 KNOWLEDGE;资源类节点
     * ({@code kind = RESOURCE})同样以悬浮方式创建——这正是
     * "先添加资源、再自己连线接入路线"的基础。
     */
    @PostMapping("/floating-nodes")
    public ResponseEntity<NodeResponse> createFloatingDraftNode(@PathVariable UUID projectId,
                                                                 @RequestBody CreateDraftNodeRequest request) {
        Node node = com.specagent.workspace.route.CommandExecution.execute(() -> commandService.createFloatingDraftNode(
                projectId, request.routeId(), request.resolveNodeKind(), request.subtype(), request.content()));
        return ResponseEntity.status(HttpStatus.CREATED).body(NodeResponse.fromFloating(node));
    }

    /**
     * 把一个已存在的悬浮节点接进路线,作为路线的新 tip——这是资源接入
     * "自己连线"的一半。只有当前 tip 才能接收新的 lineage 子节点,因此
     * 手绘连线永远不可能插进历史。节点的 id、kind、内容保持不变。
     */
    @PostMapping("/nodes/{nodeId}/connect")
    public ResponseEntity<NodeResponse> connectFloatingNode(@PathVariable UUID projectId,
                                                            @PathVariable UUID nodeId,
                                                            @RequestBody ConnectNodeRequest request) {
        Node node = com.specagent.workspace.route.CommandExecution.execute(
                () -> commandService.connectFloatingNodeToRoute(
                        projectId, request.routeId(), nodeId, request.parentNodeId()));
        return ResponseEntity.ok(NodeResponse.from(node, request.routeId(), false));
    }

    /** 把当前 tip 从其所在路线摘下,恢复成悬浮节点。 */
    @PostMapping("/nodes/{nodeId}/disconnect")
    public ResponseEntity<NodeResponse> disconnectNode(@PathVariable UUID projectId,
                                                       @PathVariable UUID nodeId,
                                                       @RequestBody DisconnectNodeRequest request) {
        Node node = com.specagent.workspace.route.CommandExecution.execute(
                () -> commandService.detachNodeFromRoute(projectId, request.routeId(), nodeId));
        return ResponseEntity.ok(NodeResponse.fromFloating(node));
    }

    /**
     * 从显式路线上的任意节点继续探索。在 tip 处追加则路线前进;
     * 从历史节点继续则创建一条显式分支路线(绝不会向历史中插入)。
     */
    @PostMapping("/nodes/{nodeId}/continuation")
    public ResponseEntity<NodeResponse> appendContinuation(@PathVariable UUID projectId,
                                                           @PathVariable UUID nodeId,
                                                           @RequestBody CreateDraftNodeRequest request) {
        GraphCommandService.ContinuationResult result = com.specagent.workspace.route.CommandExecution.execute(
                () -> commandService.appendContinuation(
                        projectId, request.routeId(), nodeId, request.subtype(), request.content()));
        return ResponseEntity.status(HttpStatus.CREATED).body(
                NodeResponse.from(result.node(), result.route().id(), result.branched()));
    }

    /** 原地编辑一个仍可编辑的用户草稿(仅 subtype/content)。 */
    @PatchMapping("/nodes/{nodeId}/draft")
    public NodeResponse reviseDraft(@PathVariable UUID projectId,
                                    @PathVariable UUID nodeId,
                                    @RequestBody ReviseDraftRequest request) {
        Node node = commandService.reviseDraftNode(projectId, nodeId, request.subtype(), request.content());
        return NodeResponse.from(node, null, false);
    }

    /** 对 claim 类节点内容做显式的知识状态流转。 */
    @PostMapping("/nodes/{nodeId}/knowledge-status")
    public NodeResponse setKnowledgeStatus(@PathVariable UUID projectId,
                                           @PathVariable UUID nodeId,
                                           @RequestBody KnowledgeStatusRequest request) {
        Node node = commandService.setKnowledgeStatus(
                projectId, nodeId, KnowledgeStatus.fromCode(request.status()));
        return NodeResponse.from(node, null, false);
    }

    /**
     * 挂载一个用户创建的资源节点(空路线的根,或追加在当前 tip)。
     * 资源是能力的上下文来源,绝不是已确认的结论。不调用任何模型。
     */
    @PostMapping("/resources")
    public ResponseEntity<NodeResponse> attachResource(@PathVariable UUID projectId,
                                                       @RequestBody AttachResourceRequest request) {
        Node node = com.specagent.workspace.route.CommandExecution.execute(() -> commandService.attachResource(
                projectId, request.routeId(), request.parentNodeId(),
                request.subtype(), request.content()));
        return ResponseEntity.status(HttpStatus.CREATED).body(
                NodeResponse.from(node, request.routeId(), false));
    }

    /** 列出全部激活的语义关系(Inspector 的数据,不是画布边)。 */
    @GetMapping("/relations")
    public List<GraphWorkspaceRelationView> listRelations(@PathVariable UUID projectId) {
        return commandService.listRelations(projectId).stream()
                .map(GraphWorkspaceRelationView::from)
                .toList();
    }

    /** 创建一条用户显式建立的语义关系。 */
    @PostMapping("/relations")
    public ResponseEntity<GraphWorkspaceRelationView> createRelation(@PathVariable UUID projectId,
                                                                     @RequestBody CreateRelationRequest request) {
        NodeRelation relation = com.specagent.workspace.route.CommandExecution.execute(() -> commandService.createSemanticRelation(
                projectId,
                request.sourceNodeId(),
                request.targetNodeId(),
                NodeRelationType.fromCode(request.relationType()),
                NodeRelation.Origin.USER,
                null,
                null));
        return ResponseEntity.status(HttpStatus.CREATED).body(GraphWorkspaceRelationView.from(relation));
    }

    /** 类型化操作日志,用于审计和撤销/重做入口。 */
    @GetMapping("/graph-operations")
    public List<GraphOperationResponse> listOperations(@PathVariable UUID projectId) {
        return commandService.listOperations(projectId).stream()
                .map(GraphOperationResponse::from)
                .toList();
    }

    @GetMapping("/graph-operations/availability")
    public Map<String, Boolean> undoRedoAvailability(@PathVariable UUID projectId) {
        return Map.of(
                "canUndo", undoRedoService.canUndo(projectId),
                "canRedo", undoRedoService.canRedo(projectId));
    }

    @PostMapping("/graph-operations/undo")
    public Map<String, Object> undo(@PathVariable UUID projectId) {
        UndoRedoService.UndoRedoResult result = com.specagent.workspace.route.CommandExecution.execute(
                () -> undoRedoService.undo(projectId));
        return undoRedoBody(result);
    }

    @PostMapping("/graph-operations/redo")
    public Map<String, Object> redo(@PathVariable UUID projectId) {
        UndoRedoService.UndoRedoResult result = com.specagent.workspace.route.CommandExecution.execute(
                () -> undoRedoService.redo(projectId));
        return undoRedoBody(result);
    }

    /**
     * 撤销/重做的响应体。{@code targetTitle} 指明被补偿的节点,让客户端
     * 能说出撤销的是"哪一个"节点("已撤销「…」"),而不只是操作类型——
     * 一次撤销可能补偿掉刚由 agent 生成的节点,用户从操作类型上认不出来。
     * 标题在这里(API 边界处)从操作的主目标读出;
     * {@link UndoRedoService} 中的补偿逻辑保持不动。
     */
    private Map<String, Object> undoRedoBody(UndoRedoService.UndoRedoResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operation", GraphOperationResponse.from(result.operation()));
        body.put("description", result.description());
        body.put("targetTitle", targetTitle(result.operation()));
        return body;
    }

    /** 操作主目标节点的展示标题,可能为 null。 */
    private String targetTitle(GraphOperation operation) {
        UUID nodeId = operation.targetNodeId();
        if (nodeId == null) {
            return null;
        }
        return nodeService.getNode(nodeId).map(GraphCommandController::displayTitle).orElse(null);
    }

    /**
     * 问题节点以它的问题为标题;其他节点以其主要文本负载
     * (用户实际写的/挂的内容)为标题。
     */
    private static String displayTitle(Node node) {
        if (node.kind() == NodeKind.INTERACTION) {
            String question = node.question();
            return question == null || question.isBlank() ? null : question;
        }
        String text = node.contentText();
        return text != null ? text : (node.question() == null || node.question().isBlank()
                ? null : node.question());
    }

    // ------------------------------------------------------------------
    // 请求/响应记录
    // ------------------------------------------------------------------

    /**
     * {@link GraphOperation} 的 JSON 安全投影。领域类暴露的是
     * record 风格访问器({@code id()}、{@code type()} 等),Jackson 的
     * 默认 bean 探测看不到它们,直接序列化领域对象会报
     * "no properties discovered"——结果是撤销/重做事务已提交,
     * 响应却 500。所以有了这层转换。
     */
    public record GraphOperationResponse(UUID id,
                                         UUID projectId,
                                         String actor,
                                         String type,
                                         List<UUID> targets,
                                         Map<String, Object> beforeRefs,
                                         Map<String, Object> afterRefs,
                                         String causedBy,
                                         boolean reversible,
                                         String status,
                                         Instant createdAt,
                                         Instant undoneAt) {

        static GraphOperationResponse from(GraphOperation operation) {
            return new GraphOperationResponse(
                    operation.id(),
                    operation.projectId(),
                    operation.actor().name(),
                    operation.type().name(),
                    operation.targets(),
                    operation.beforeRefs(),
                    operation.afterRefs(),
                    operation.causedBy(),
                    operation.reversible(),
                    operation.status().name(),
                    operation.createdAt(),
                    operation.undoneAt());
        }
    }

    /**
     * 创建草稿节点的请求体。
     *
     * {@code nodeKind} 是可选的,且只有悬浮节点端点会理会它
     * (缺省 KNOWLEDGE):/nodes 和 /nodes/{id}/continuation 的 kind 固定,
     * 防止一个多余字段把既有流程的节点悄悄变成资源。
     */
    public record CreateDraftNodeRequest(UUID routeId,
                                         String subtype,
                                         Map<String, Object> content,
                                         String nodeKind) {

        NodeKind resolveNodeKind() {
            return nodeKind == null || nodeKind.isBlank()
                    ? NodeKind.KNOWLEDGE
                    : NodeKind.fromCode(nodeKind);
        }
    }

    public record ConnectNodeRequest(UUID routeId, UUID parentNodeId) {
    }

    public record DisconnectNodeRequest(UUID routeId) {
    }

    public record ReviseDraftRequest(String subtype, Map<String, Object> content) {
    }

    public record KnowledgeStatusRequest(String status) {
    }

    public record CreateRelationRequest(UUID sourceNodeId, UUID targetNodeId, String relationType) {
    }

    public record AttachResourceRequest(UUID routeId,
                                        UUID parentNodeId,
                                        String subtype,
                                        Map<String, Object> content) {
    }

    public record NodeResponse(UUID id,
                               UUID routeId,
                               boolean branched,
                               String kind,
                               String subtype,
                               Map<String, Object> content,
                               String authorKind,
                               String knowledgeStatus) {

        static NodeResponse from(Node node, UUID routeId, boolean branched) {
            return new NodeResponse(
                    node.id(), routeId, branched,
                    node.kind().code(), node.subtype(), node.content(),
                    node.authorKind().code(),
                    node.knowledgeStatus() == null ? null : node.knowledgeStatus().code());
        }

        /**
         * 悬浮(无路线)响应:routeId 恒为 null。创建上下文的路线 id
         * 只记入操作日志;响应本身绝不声称节点属于任何路线。
         */
        static NodeResponse fromFloating(Node node) {
            return new NodeResponse(
                    node.id(), null, false,
                    node.kind().code(), node.subtype(), node.content(),
                    node.authorKind().code(),
                    node.knowledgeStatus() == null ? null : node.knowledgeStatus().code());
        }
    }
}

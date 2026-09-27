package com.specagent.agent.action;

import com.specagent.agent.protocol.ActionFamily;
import com.specagent.agent.protocol.ActionProposal;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.CapabilityRuntime;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeOption;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:ProposalActionExecutor.java
 *
 * 用途:ActionExecutor 的具体实现,按动作族分发已通过校验的动作提案。
 *
 * 会产生图变更的动作族(REQUEST_USER_INPUT、CREATE_NODE)委托给
 * {@link AgentGraphMutationService}——图操作的窄事务边界:节点插入与
 * 路由 tip 推进在项目行锁下一起提交,期望锚点在该事务内对照当前路由
 * tip 重新校验。自动执行在模型与策略完成后才进入;已接受的提案经由
 * 接受事务进入同一边界。只读的执行族与 INVOKE_CAPABILITY 保持在图锁之外。
 *
 * 每次执行之前都已做过 stale 上下文存活检查。执行器从不自行发明 ID;
 * 所有身份分配都委托给 Runtime 服务。
 */
@Component
public class ProposalActionExecutor implements ActionExecutor {

    private final CapabilityRuntime capabilityRuntime;
    private final AgentGraphMutationService agentGraphMutationService;

    public ProposalActionExecutor(CapabilityRuntime capabilityRuntime,
                                  AgentGraphMutationService agentGraphMutationService) {
        this.capabilityRuntime = capabilityRuntime;
        this.agentGraphMutationService = agentGraphMutationService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ActionResult execute(ActionProposal proposal, ActionExecutionContext context) {
        ActionFamily family = ActionFamily.fromCode(proposal.actionFamily());
        return switch (family) {
            case REQUEST_USER_INPUT -> executeRequestUserInput(proposal, context);
            case CREATE_NODE -> executeCreateNode(proposal, context);
            case RESPOND_TO_USER -> executeRespondToUser(proposal);
            case WAIT -> executeWait();
            case INVOKE_CAPABILITY -> executeInvokeCapability(proposal, context);
            case UPDATE_NODE, CONNECT_NODE, CREATE_ROUTE ->
                    executeDeferredMutation(proposal);
            case GENERATE_ARTIFACT ->
                    executeUnsupported(proposal);
        };
    }

    /**
     * 通过 CapabilityRuntime 调用能力,使用 Runtime 持有的幂等键:重试同一
     * 提案会回放已记录的结果(若调用尚未结束则返回类型化的 IN_PROGRESS 状态),
     * 而不是重复执行适配器。类型化失败以消息形式返回,供 run 上报而不是崩溃。
     */
    @SuppressWarnings("unchecked")
    private ActionResult executeInvokeCapability(ActionProposal proposal,
                                                 ActionExecutionContext context) {
        Map<String, Object> payload = proposal.payload();
        String capabilityId = asString(payload.get("capabilityId"), "capabilityId");
        Map<String, Object> arguments = payload.get("arguments") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();

        String invocationKey = "run:" + context.runId() + ":proposal:" + proposal.idempotencyKey();
        CapabilityResult result = capabilityRuntime.invoke(
                invocationKey, capabilityId, context.projectId(), context.runId(), arguments);

        String message = "capability " + capabilityId + " -> " + result.status()
                + (result.status() == CapabilityResult.Status.FAILED
                        ? ": " + result.content().get("reason") : "");
        return new ActionResult("INVOKE_CAPABILITY", null, null, message);
    }

    @SuppressWarnings("unchecked")
    private ActionResult executeRequestUserInput(ActionProposal proposal,
                                                 ActionExecutionContext context) {
        Map<String, Object> payload = proposal.payload();
        String questionText = asString(payload.get("questionText"), "questionText");
        String purpose = asString(payload.get("purpose"), "purpose");
        boolean allowFreeAnswer = payload.get("allowFreeAnswer") instanceof Boolean b && b;
        boolean allowMultiSelect = payload.get("allowMultiSelect") instanceof Boolean b && b;
        List<NodeOption> options = parseOptions(payload.get("options"));

        // 锚点是模型决策时的路由 tip(空路由为 null)。事务边界会在插入前
        // 对照当前 tip 重新校验;无锚点的决策若路由此后已产生 tip,
        // 会 fail-closed 而不是错误地锚定到新节点上。
        Node node = agentGraphMutationService.executeNodeCreation(
                context.projectId(), context.routeId(), context.anchorNodeId(),
                new AgentGraphMutationService.InteractionNode(
                        questionText, purpose, options, allowFreeAnswer, allowMultiSelect),
                "proposal:" + proposal.proposalId());

        return new ActionResult("REQUEST_USER_INPUT", node.id(), null, null);
    }

    @SuppressWarnings("unchecked")
    private ActionResult executeCreateNode(ActionProposal proposal,
                                           ActionExecutionContext context) {
        Map<String, Object> payload = proposal.payload();
        String kind = payload.get("kind") instanceof String s ? s : "INTERACTION";

        if (!"INTERACTION".equals(kind)) {
            // 通用 workspace 单元:载荷放在 content 里;subtype 白名单
            // 由 Runtime 在创建时校验。
            String subtype = asString(payload.get("subtype"), "subtype");
            Map<String, Object> content = payload.get("content") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            Node node = agentGraphMutationService.executeNodeCreation(
                    context.projectId(), context.routeId(), context.anchorNodeId(),
                    new AgentGraphMutationService.WorkspaceNode(
                            com.specagent.workspace.node.NodeKind.fromCode(kind),
                            subtype, content),
                    "proposal:" + proposal.proposalId());
            return new ActionResult("CREATE_NODE", node.id(), null, null);
        }

        // 交互节点:提问载荷保持权威。同时接受文档规定的 questionText 键
        // 与遗留的 question 键。
        String questionText = payload.get("questionText") instanceof String q && !q.isBlank()
                ? q : asString(payload.get("question"), "question");
        String purpose = asString(payload.get("purpose"), "purpose");
        boolean allowFreeAnswer = payload.get("allowFreeAnswer") instanceof Boolean b && b;
        boolean allowMultiSelect = payload.get("allowMultiSelect") instanceof Boolean b && b;
        List<NodeOption> options = parseOptions(payload.get("options"));

        Node node = agentGraphMutationService.executeNodeCreation(
                context.projectId(), context.routeId(), context.anchorNodeId(),
                new AgentGraphMutationService.InteractionNode(
                        questionText, purpose, options, allowFreeAnswer, allowMultiSelect),
                "proposal:" + proposal.proposalId());

        return new ActionResult("CREATE_NODE", node.id(), null, null);
    }

    private ActionResult executeRespondToUser(ActionProposal proposal) {
        String message = asString(proposal.payload().get("message"), "message");
        return new ActionResult("RESPOND_TO_USER", null, null, message);
    }

    private ActionResult executeWait() {
        return new ActionResult("WAIT", null, null, null);
    }

    private ActionResult executeDeferredMutation(ActionProposal proposal) {
        throw new UnsupportedOperationException(
                "Action family " + proposal.actionFamily()
                        + " requires confirmation and is not yet executable in Stage B");
    }

    private ActionResult executeUnsupported(ActionProposal proposal) {
        throw new UnsupportedOperationException(
                "Action family " + proposal.actionFamily()
                        + " is not supported in Stage B (no capability/artifact runtime)");
    }

    @SuppressWarnings("unchecked")
    private List<NodeOption> parseOptions(Object optionsObj) {
        if (!(optionsObj instanceof List<?> optionList)) {
            return List.of();
        }
        List<NodeOption> result = new ArrayList<>();
        for (Object item : optionList) {
            if (item instanceof Map<?, ?> map) {
                String label = asString(map.get("label"), "option.label");
                // recommended:模型基于上下文给出的建议,在 UI 中显示为角标;
                // 它只是建议,绝不是预先选中的答案。
                boolean recommended = map.get("recommended") instanceof Boolean b && b;
                result.add(new NodeOption(UUID.randomUUID(), label, null, recommended));
            }
        }
        return result;
    }

    private String asString(Object value, String name) {
        if (value instanceof String s) {
            return s;
        }
        return null;
    }
}

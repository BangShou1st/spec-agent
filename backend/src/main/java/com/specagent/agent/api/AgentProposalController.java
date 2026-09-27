package com.specagent.agent.api;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.policy.AgentProposal;
import com.specagent.agent.policy.AgentProposalService;
import com.specagent.agent.runtime.ProposalAcceptanceService;
import com.specagent.agent.policy.ProposalStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 文件名:AgentProposalController.java
 *
 * 用途:Advisor 提案生命周期 REST API。列表接口按请求的状态过滤,
 * 并可按产出提案的 run 触发类型进一步过滤;接受提案时会对照当前图状态
 * 重新校验,并在一个事务内通过 Runtime 命令层执行。
 *
 * 协作:被前端工作台调用,用于浏览/接受/拒绝 proposal;
 * 接受与拒绝分别走 ProposalAcceptanceService 和 AgentProposalService。
 */
@RestController
@RequestMapping("/api/v1")
public class AgentProposalController {

    private final AgentProposalService proposalService;
    private final ProposalAcceptanceService acceptanceService;
    private final AgentRunService agentRunService;

    public AgentProposalController(AgentProposalService proposalService,
                                   ProposalAcceptanceService acceptanceService,
                                   AgentRunService agentRunService) {
        this.proposalService = proposalService;
        this.acceptanceService = acceptanceService;
        this.agentRunService = agentRunService;
    }

    /**
     * 列出提案,可按产出 run 的触发类型进一步收窄。
     *
     * 两个过滤参数都是可选的、逗号分隔
     * ({@code AgentRunTriggerType} 代码,如 {@code node_query});
     * 省略时返回与之前完全一致的未过滤列表。
 * - {@code triggerType} —— 只保留 run 触发类型命中的提案。
 * - {@code excludeTriggerType} —— 剔除 run 触发类型命中的提案。
     *
     * 提案对应的 run 记录已不存在时触发类型未知({@code null}):
     * 会被包含过滤剔除、被排除过滤保留,与之前客户端过滤的行为一致。
     */
    @GetMapping("/projects/{projectId}/proposals")
    public ResponseEntity<List<Map<String, Object>>> listProposals(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "PROPOSED") String status,
            @RequestParam(name = "triggerType", required = false) String triggerType,
            @RequestParam(name = "excludeTriggerType", required = false) String excludeTriggerType) {
        ProposalStatus proposalStatus = ProposalStatus.fromCode(status);
        Set<String> include = triggerTypeCodes(triggerType);
        Set<String> exclude = triggerTypeCodes(excludeTriggerType);

        // 整个页面只做一次项目级 run 读取,而不是每个提案两次 getRun()
        // 往返(之前的 N+1 问题)。
        Map<UUID, AgentRun> runsById = agentRunService.listByProject(projectId).stream()
                .collect(Collectors.toMap(AgentRun::id, run -> run, (first, second) -> first));

        List<Map<String, Object>> summaries = new ArrayList<>();
        for (AgentProposal proposal : proposalService.getByStatus(projectId, proposalStatus)) {
            String resolvedTriggerType = resolveTriggerType(proposal.runId(), runsById);
            if (!matchesTriggerFilter(resolvedTriggerType, include, exclude)) {
                continue;
            }
            summaries.add(toSummary(proposal, resolvedTriggerType,
                    resolveInputNodeId(proposal.runId(), runsById)));
        }
        return ResponseEntity.ok(summaries);
    }

    @PostMapping("/proposals/{proposalId}/accept")
    public ResponseEntity<Map<String, Object>> acceptProposal(
            @PathVariable UUID proposalId) {
        ProposalAcceptanceService.AcceptedProposalResult result =
                acceptanceService.acceptAndExecute(proposalId, "user");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("proposalId", proposalId.toString());
        body.put("status", "ACCEPTED");
        body.put("actionFamily", result.actionFamily());
        body.put("producedNodeId", result.producedNodeId() == null
                ? null : result.producedNodeId().toString());
        body.put("relationId", result.relationId() == null
                ? null : result.relationId().toString());
        body.put("originRunId", result.originRunId() == null
                ? null : result.originRunId().toString());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/proposals/{proposalId}/reject")
    public ResponseEntity<Map<String, Object>> rejectProposal(
            @PathVariable UUID proposalId) {
        proposalService.rejectProposal(proposalId, "user");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("proposalId", proposalId.toString());
        body.put("status", "REJECTED");
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> toSummary(AgentProposal proposal,
                                          String triggerType,
                                          String inputNodeId) {
        // 使用 LinkedHashMap(而非 Map.of),使 PROPOSED 状态提案的
        // decidedAt/decidedBy 为 null 时不会抛 NullPointerException。
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("proposalId", proposal.id().toString());
        summary.put("runId", proposal.runId() == null ? null : proposal.runId().toString());
        summary.put("triggerType", triggerType);
        summary.put("inputNodeId", inputNodeId);
        summary.put("routeId", proposal.routeId() == null ? null : proposal.routeId().toString());
        summary.put("actionFamily", proposal.actionFamily());
        summary.put("status", proposal.status().code());
        summary.put("createdAt", proposal.createdAt().toString());
        summary.put("decidedAt", proposal.decidedAt() != null ? proposal.decidedAt().toString() : null);
        summary.put("decidedBy", proposal.decidedBy() != null ? proposal.decidedBy() : null);
        return summary;
    }

    /** 解析逗号分隔的触发类型代码列表;为空表示"不过滤"。 */
    private static Set<String> triggerTypeCodes(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean matchesTriggerFilter(String triggerType,
                                                Set<String> include,
                                                Set<String> exclude) {
        if (triggerType != null && exclude.contains(triggerType)) {
            return false;
        }
        if (!include.isEmpty()) {
            return triggerType != null && include.contains(triggerType);
        }
        return true;
    }

    /**
     * 产出该提案的 run 触发类型,由提案关联的 AgentRun 推导。
     * 前端绝不得从 {@code inputNodeId} 推断查询来源——每种 run 都有
     * inputNodeId——因此触发类型才是把 NodeQuery 恢复限定在
     * node_query 提案上的显式过滤依据。run 已不存在时为 null。
     */
    private static String resolveTriggerType(UUID runId, Map<UUID, AgentRun> runsById) {
        if (runId == null) {
            return null;
        }
        AgentRun run = runsById.get(runId);
        return run == null ? null : run.triggerType().code();
    }

    /**
     * 产出该提案的 NodeQuery run 的规范锚点节点,使前端即使在页面刷新后
     * 也能把待决提案重新连到 Inspector 的锚点上。run 已不存在时为 null。
     */
    private static String resolveInputNodeId(UUID runId, Map<UUID, AgentRun> runsById) {
        if (runId == null) {
            return null;
        }
        AgentRun run = runsById.get(runId);
        if (run == null || run.inputNodeId() == null) {
            return null;
        }
        return run.inputNodeId().toString();
    }
}

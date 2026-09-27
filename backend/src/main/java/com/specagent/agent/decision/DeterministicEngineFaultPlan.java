package com.specagent.agent.decision;

import com.specagent.agent.protocol.AgentRequestEnvelope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 文件名:DeterministicEngineFaultPlan.java
 *
 * 用途:JVM 内确定性引擎({@code spec.agent.brain.engine=fake})使用的
 * 自包含、确定性的故障注入计划。
 *
 * <strong>为什么存在。</strong>有些产品状态从设计上就无法通过公开的
 * UI/API 达到——最典型的是"回答已持久化,但其 STATE_UPDATE checkpoint
 * 缺失"。正是这个状态会触发 {@code ANSWER_CYCLE_INCOMPLETE},也正是
 * 历史回答恢复 UX 所服务的场景。产品已刻意拒绝再制造这个状态(DRAFT
 * 路径不会跨过未处理的回答),所以浏览器测试若不经一次刻意、显式声明的
 * 失败,就无法通过真实用户操作到达它。本计划提供的正是这个失败。
 *
 * <strong>伪造什么、不伪造什么。</strong>只有被标记 run 的 STATE_UPDATE
 * 结果被强制失败。其后所有持久化的产物——落库的 Answer、失败 run 记录、
 * gate 拒绝、恢复 run、生成的 AnswerPatch、产出的 spec——都由普通运行时
 * 原样产出,不做任何改动。
 *
 * <strong>作用范围。</strong>三条互相独立的限制保证它在其他场景完全
 * 失效(inert):
 * - 只在选中确定性引擎时才注册;正常产品配置({@code remote-python})
 *       永远不会创建它;
 * - 只有提交的自由文本中携带指令的 run 会受影响——同一 JVM 中的其他
 *       run 完全不受影响;
 * - 预算按"已回答节点"隔离,且恰好消耗声明的调用次数,因此"失败两次
 *       然后成功"是确定性的,其他测试也无法消耗该预算(无全局计数器,
 *       不依赖时序)。 */
@Component
@ConditionalOnProperty(name = "spec.agent.brain.engine", havingValue = "fake")
public class DeterministicEngineFaultPlan {

    /**
     * 由提交的回答文本携带的指令:{@code [[fail-state-update:N]]} 会让该
     * 已回答节点接下来的 {@code N} 次 STATE_UPDATE 调用失败。指令缺失或
     * 格式错误时会被忽略,因此普通回答文本永远不会被误读。
     */
    public static final String DIRECTIVE_PREFIX = "[[fail-state-update:";
    public static final String DIRECTIVE_SUFFIX = "]]";

    /**
     * 由回答文本武装、在随后的独立起草/续跑 run 上引爆的确定性失败:
     * {@code [[fail-decision:N]]} 让锚定在该节点的接下来 {@code N} 次
     * DECISION(事件 kind 非 ANSWER_SUBMITTED/NODE_QUERY——即独立的起草、
     * fork 分支首问、续跑与换题)失败。它填补了起草失败占位卡路径的
     * 浏览器验证空白:回答已保存且 STATE_UPDATE 成功,但"下一问题生成"
     * 这个独立 run 失败。指令同样只在确定性引擎内生效,普通文本不携带
     * 该前缀时被忽略。
     */
    public static final String DECISION_DIRECTIVE_PREFIX = "[[fail-decision:";

    /**
     * 仅测试环境可用的确定性延迟指令(第三轮复核 R3-B 的浏览器验收):
     * {@code [[delay-decision-ms:N]]} 让锚定在该节点的<strong>接下来三次</strong>
     * 独立 DECISION 调用各延迟 {@code N} 毫秒——依次对应"回答后的自治续跑、
     * 失败的首次起草、用户点下的重试",最后一次为浏览器验收制造一个
     * 确定性的"重试运行中"窗口(在窗口内硬刷新,再断言产物自动出现)。
     * 延迟发生在
     * 确定性引擎内部,只在 {@code engine=fake} 时注册,正常产品配置永远
     * 不会创建本组件;不依赖碰运气的 sleep 排序。
     */
    public static final String DECISION_DELAY_PREFIX = "[[delay-decision-ms:";

    /**
     * 由回答文本武装、在随后的规格生成 run 上引爆的确定性失败(R5-A 的
     * 浏览器验收基础设施):{@code [[fail-artifact:N]]} 让有效历史中包含
     * 该已回答节点的接下来 {@code N} 次 ARTIFACT_GENERATION 失败。引爆按
     * <strong>快照 lineage 匹配</strong>而非生成时的锚点节点——规格生成
     * 信封的锚是路线 tip,而自治续跑会把 tip 推进到别的问题节点,按锚点
     * 匹配永远打不中。指令同样只在确定性引擎内生效,普通文本不携带该前缀
     * 时被忽略。
     */
    public static final String ARTIFACT_DIRECTIVE_PREFIX = "[[fail-artifact:";

    /** 延迟指令生效的独立 DECISION 调用次数(自治续跑 + 首次起草 + 重试)。 */
    static final int DECISION_DELAY_CALL_BUDGET = 3;

    private final Map<UUID, AtomicInteger> budgetsByNode = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> decisionBudgetsByNode = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> artifactBudgetsByNode = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> decisionDelayCallsByNode = new ConcurrentHashMap<>();
    /** 由回答文本武装的每节点延迟毫秒数(与失败预算相同的 arm-then-trigger 模式)。 */
    private final Map<UUID, Long> decisionDelayMsByNode = new ConcurrentHashMap<>();

    /**
     * 当已回答节点携带已武装(armed)的指令时,故意让本次 STATE_UPDATE 失败;
     * 否则正常返回。
     *
     * @throws IllegalStateException 声明好的、类型化的失败,调用方必须把它
     *         观察为一次失败的 run——刻意不使用模型契约或 Brain 可用性类
     *         错误,避免在 run 事件或恢复文案中与真实的提供方结果混淆
     */
    public void failStateUpdateIfDirected(AgentRequestEnvelope request) {
        if (request == null || request.event() == null) {
            return;
        }
        int declared = declaredBudget(request.event().freeText());
        if (declared <= 0) {
            return;
        }
        UUID nodeId = request.event().anchorNodeId();
        if (nodeId == null) {
            return;
        }
        AtomicInteger remaining = budgetsByNode.computeIfAbsent(
                nodeId, key -> new AtomicInteger(declared));
        int current = remaining.get();
        while (current > 0) {
            if (remaining.compareAndSet(current, current - 1)) {
                throw new IllegalStateException(
                        "DETERMINISTIC_ENGINE_FAULT: STATE_UPDATE failed on purpose for node "
                                + nodeId + " (" + current + " of " + declared
                                + " declared failures remaining)");
            }
            current = remaining.get();
        }
    }

    /** 某个节点剩余的声明失败次数;供测试断言使用。 */
    public int remainingFailures(UUID nodeId) {
        AtomicInteger remaining = budgetsByNode.get(nodeId);
        return remaining == null ? 0 : remaining.get();
    }

    /**
     * 从 STATE_UPDATE 的回答文本中武装对后续独立 DECISION(起草)的失败
     * 预算。武装只在确定性引擎内生效,预算按节点隔离且不叠加(与
     * {@link #failStateUpdateIfDirected} 相同的 computeIfAbsent 语义,
     * 恢复重放不会重复武装)。
     */
    /**
     * 从 STATE_UPDATE 的回答文本中武装对后续独立 DECISION 的确定性延迟
     * (与 {@link #armDecisionFaultIfDirected} 相同的 arm-then-trigger 模式:
     * 独立起草 run 自己的 freeText 不携带回答文本,延迟毫秒数必须在这里
     * 一次性武装,之后按调用次数消耗)。
     */
    public void armDecisionDelayIfDirected(AgentRequestEnvelope request) {
        if (request == null || request.event() == null) {
            return;
        }
        long delayMs = declaredDelayMs(request.event().freeText());
        if (delayMs <= 0) {
            return;
        }
        UUID nodeId = request.event().anchorNodeId();
        if (nodeId == null) {
            return;
        }
        decisionDelayMsByNode.putIfAbsent(nodeId, delayMs);
        decisionDelayCallsByNode.computeIfAbsent(nodeId,
                key -> new AtomicInteger(DECISION_DELAY_CALL_BUDGET));
    }

    public void armDecisionFaultIfDirected(AgentRequestEnvelope request) {
        if (request == null || request.event() == null) {
            return;
        }
        int declared = declaredBudget(request.event().freeText(), DECISION_DIRECTIVE_PREFIX);
        if (declared <= 0) {
            return;
        }
        UUID nodeId = request.event().anchorNodeId();
        if (nodeId == null) {
            return;
        }
        decisionBudgetsByNode.computeIfAbsent(nodeId, key -> new AtomicInteger(declared));
    }

    /**
     * 从 STATE_UPDATE 的回答文本中武装对后续规格生成(ARTIFACT_GENERATION)
     * 的失败预算。语义与 {@link #armDecisionFaultIfDirected} 相同:预算按
     * 已回答节点隔离且不叠加,恢复重放不会重复武装。
     */
    public void armArtifactFaultIfDirected(AgentRequestEnvelope request) {
        if (request == null || request.event() == null) {
            return;
        }
        int declared = declaredBudget(request.event().freeText(), ARTIFACT_DIRECTIVE_PREFIX);
        if (declared <= 0) {
            return;
        }
        UUID nodeId = request.event().anchorNodeId();
        if (nodeId == null) {
            return;
        }
        artifactBudgetsByNode.computeIfAbsent(nodeId, key -> new AtomicInteger(declared));
    }

    /**
     * 规格生成执行前的确定性失败检查:快照有效历史(lineage)中包含任何
     * 带已武装 {@code [[fail-artifact:N]]} 指令的已回答节点时,消耗一次
     * 预算并失败。按 lineage 匹配使失败独立于自治续跑对 tip 的推进。
     *
     * @throws IllegalStateException 声明好的、类型化的失败,调用方必须把
     *         它观察为一次失败的规格生成 run
     */
    public void failArtifactIfDirected(AgentRequestEnvelope request) {
        if (request == null || request.snapshot() == null) {
            return;
        }
        for (var entry : request.snapshot().lineage()) {
            UUID nodeId = entry.node().id();
            AtomicInteger remaining = artifactBudgetsByNode.get(nodeId);
            if (remaining == null) {
                continue;
            }
            int current = remaining.get();
            while (current > 0) {
                if (remaining.compareAndSet(current, current - 1)) {
                    throw new IllegalStateException(
                            "DETERMINISTIC_ENGINE_FAULT: ARTIFACT_GENERATION failed on purpose "
                                    + "for armed answered node " + nodeId + " (" + current
                                    + " declared failures were armed)");
                }
                current = remaining.get();
            }
        }
    }

    /**
     * 独立 DECISION(起草/续跑/换题)执行前的确定性失败检查。回答周期
     * 内部的 DECISION(kind=ANSWER_SUBMITTED)与只读的 NODE_QUERY 不消耗
     * 预算——前者失败属于"回答已保存,后续处理未完成"路径,后者是无
     * 副作用的查询。
     *
     * @throws IllegalStateException 声明好的、类型化的失败,调用方必须把
     *         它观察为一次失败的起草 run
     */
    public void failDecisionIfDirected(AgentRequestEnvelope request) {
        if (request == null || request.event() == null) {
            return;
        }
        String kind = request.event().kind();
        if ("ANSWER_SUBMITTED".equals(kind) || "NODE_QUERY".equals(kind)) {
            return;
        }
        UUID nodeId = request.event().anchorNodeId();
        if (nodeId == null) {
            return;
        }
        AtomicInteger remaining = decisionBudgetsByNode.get(nodeId);
        if (remaining == null) {
            return;
        }
        int current = remaining.get();
        while (current > 0) {
            if (remaining.compareAndSet(current, current - 1)) {
                throw new IllegalStateException(
                        "DETERMINISTIC_ENGINE_FAULT: DECISION (draft) failed on purpose "
                                + "for node " + nodeId + " (" + current + " declared "
                                + "failures were armed)");
            }
            current = remaining.get();
        }
    }

    /**
     * 独立 DECISION(起草/续跑/换题)执行前的确定性延迟(仅测试环境)。
     * 回答周期内部的 DECISION(kind=ANSWER_SUBMITTED)与只读 NODE_QUERY
     * 不受影响,与 {@link #failDecisionIfDirected} 的豁免一致。
     */
    public void delayDecisionIfDirected(AgentRequestEnvelope request) {
        if (request == null || request.event() == null) {
            return;
        }
        UUID nodeId = request.event().anchorNodeId();
        if (nodeId == null) {
            return;
        }
        Long armedDelayMs = decisionDelayMsByNode.get(nodeId);
        if (armedDelayMs == null || armedDelayMs <= 0) {
            return;
        }
        AtomicInteger remainingCalls = decisionDelayCallsByNode.get(nodeId);
        if (remainingCalls == null || remainingCalls.getAndDecrement() <= 0) {
            return;
        }
        try {
            Thread.sleep(armedDelayMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static long declaredDelayMs(String freeText) {
        if (freeText == null) {
            return 0;
        }
        int start = freeText.indexOf(DECISION_DELAY_PREFIX);
        if (start < 0) {
            return 0;
        }
        int valueStart = start + DECISION_DELAY_PREFIX.length();
        int end = freeText.indexOf(DIRECTIVE_SUFFIX, valueStart);
        if (end < 0) {
            return 0;
        }
        try {
            return Math.max(0, Math.min(30_000, Long.parseLong(
                    freeText.substring(valueStart, end).strip())));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static int declaredBudget(String freeText) {
        return declaredBudget(freeText, DIRECTIVE_PREFIX);
    }

    private static int declaredBudget(String freeText, String prefix) {
        if (freeText == null) {
            return 0;
        }
        int start = freeText.indexOf(prefix);
        if (start < 0) {
            return 0;
        }
        int valueStart = start + prefix.length();
        int end = freeText.indexOf(DIRECTIVE_SUFFIX, valueStart);
        if (end < 0) {
            return 0;
        }
        try {
            return Math.max(0, Math.min(10, Integer.parseInt(
                    freeText.substring(valueStart, end).strip())));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}

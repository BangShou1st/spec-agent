package com.specagent.agent.runtime;

import com.specagent.runtime.execution.RecoveryAdapter;
import com.specagent.runtime.execution.RecoveryVerdict;
import com.specagent.runtime.execution.RuntimeTask;
import com.specagent.runtime.execution.WorkspaceRunStep;
import com.specagent.runtime.execution.WorkspaceRunStepRepository;
import com.specagent.runtime.execution.WorkspaceRunStepState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 文件名:WorkspaceAnswerChainRecoveryAdapter.java
 *
 * 用途:画布 Answer/Patch 链的接管安全性核验(design §7、§12)。这是第一阶段
 * <b>唯一</b>被授权自动恢复的 task_type:其余画布类型使用租约过期后的诚实失败。
 *
 * <p><b>它只读。</b>适配器绝不发起任何外部调用(那正是它要判定的对象),也绝不
 * 改变业务状态。它的唯一职责是回答"现在接管安全吗"。
 *
 * <p><b>核验依据(design §7 表格)。</b>逐条对照持久化事实:
 *
 * <ol>
 *   <li><b>不可重放的步骤结果不确定</b> —— 任一 {@code UNSAFE} 类步骤处于
 *       {@code IN_FLIGHT}/{@code UNKNOWN},说明外部调用可能已经发生而没有留下
 *       结果。接管会重复副作用 → <b>不安全</b>,诚实失败并留下核对记录。</li>
 *   <li><b>Answer 已提交</b> —— 步骤 {@code answer_persisted} 为 SUCCEEDED 且
 *       {@code answers} 行存在。接管必须复用同一个 Answer,
 *       绝不创建第二个(由 {@code (route,node)} 唯一约束在数据库层兜底)。</li>
 *   <li><b>Patch 已提交</b> —— 步骤 SUCCEEDED 且 {@code answer_patches} 行存在,
 *       且与该 Answer 唯一对应。接管复用它,绝不重跑 STATE_UPDATE。</li>
 *   <li><b>结果引用可验证</b> —— 步骤的 {@code result_ref} 必须真的指向存在的
 *       行。引用悬空说明"标记了成功但结果不存在",接管会基于错误前提继续 →
 *       <b>不安全</b>。</li>
 *   <li><b>模型步骤的安全边界</b> —— {@code state_update_model} /
 *       {@code decision_model} 属于 {@code UNSAFE}。若它们 SUCCEEDED 但结果
 *       <b>已完整持久化</b>(对应的 patch/node 步骤也 SUCCEEDED 且引用有效),
 *       接管可以从核验过的 checkpoint 继续,不需要重新推理;若它们停留在
 *       IN_FLIGHT/UNKNOWN,则不接管。</li>
 * </ol>
 *
 * <p>safe 判定<b>不等于</b>"模型只执行一次"(design §12 末句):接管可能让无副
 * 作用的模型步骤重新推理,承认可能的重复计费;有副作用的外部调用一律不重放。
 */
@Component
public class WorkspaceAnswerChainRecoveryAdapter implements RecoveryAdapter {

    private static final Logger LOG =
            LoggerFactory.getLogger(WorkspaceAnswerChainRecoveryAdapter.class);

    /**
     * 本适配器授权的 task_type(design §13:P1-C 先支持这一条链)。
     * 与 {@code AgentRunTriggerType.ANSWER_CYCLE.code()} 保持一致(task_type
     * 就是 trigger code,小写下划线)。
     */
    private static final java.util.Set<String> SUPPORTED_TASK_TYPES =
            java.util.Set.of(AgentRunTriggerType.ANSWER_CYCLE.code());

    private final WorkspaceRunStepRepository steps;
    private final AnswerChainFacts facts;

    public WorkspaceAnswerChainRecoveryAdapter(WorkspaceRunStepRepository steps,
                                              AnswerChainFacts facts) {
        this.steps = steps;
        this.facts = facts;
    }

    @Override
    public String taskType() {
        return AgentRunTriggerType.ANSWER_CYCLE.code();
    }

    /** 是否为受支持的 task_type(调度层按 task_type 精确匹配,这里是自检)。 */
    public boolean supports(String taskType) {
        return SUPPORTED_TASK_TYPES.contains(taskType);
    }

    @Override
    public RecoveryVerdict assess(RuntimeTask task) {
        UUID runId = task.workspaceRunId();
        if (runId == null) {
            return RecoveryVerdict.unsafe("recovery adapter received a non-workspace task",
                    "task=" + task.id());
        }
        List<WorkspaceRunStep> recorded = steps.findByRun(runId);
        String base = "task=" + task.id() + " run=" + runId
                + " attempt=" + task.attemptCount();

        // (1) 不可重放的步骤结果不确定 → 绝不接管。
        for (WorkspaceRunStep step : recorded) {
            if (step.outcomeUnknown()
                    && step.retryClass()
                    == com.specagent.runtime.execution.WorkspaceRunStepRetryClass.UNSAFE) {
                return RecoveryVerdict.unsafe(
                        "step " + step.stepKey() + " has an UNKNOWN outcome and is not safely "
                                + "replayable; refusing automatic takeover",
                        base + " unknownStep=" + step.stepKey()
                                + " originEpoch=" + step.originEpoch()
                                + " ref=" + step.resultRef());
            }
        }

        // (2)(4) 每个 SUCCEEDED 步骤的结果引用必须真的存在。
        for (WorkspaceRunStep step : recorded) {
            if (!step.succeeded()) {
                continue;
            }
            String dangling = facts.danglingReference(step);
            if (dangling != null) {
                return RecoveryVerdict.unsafe(
                        "step " + step.stepKey() + " is marked SUCCEEDED but its result "
                                + "reference cannot be verified: " + dangling,
                        base + " danglingStep=" + step.stepKey()
                                + " ref=" + step.resultRef());
            }
        }

        // (3) Answer/Patch 复用前提:Answer 与 patch 至多各一个,且 patch 属于该 Answer。
        ChainShape shape = facts.chainShape(runId);
        if (shape.answerCount() > 1) {            return RecoveryVerdict.unsafe(
                    "run already produced " + shape.answerCount()
                            + " answers; a takeover could duplicate immutable answers",
                    base + " answerCount=" + shape.answerCount());
        }
        if (shape.patchCount() > 1) {
            return RecoveryVerdict.unsafe(
                    "run already produced " + shape.patchCount()
                            + " patches; a takeover could duplicate checkpoints",
                    base + " patchCount=" + shape.patchCount());
        }
        if (shape.patchWithoutAnswer()) {
            return RecoveryVerdict.unsafe(
                    "a patch exists whose source answer is missing; refusing to continue "
                            + "from an inconsistent checkpoint",
                    base);
        }

        // (5) 模型步骤若已完整持久化,可以复用 checkpoint 继续;若它仍在飞行中
        //     则拒绝接管(结果未持久化,重放可能重复计费)。
        for (WorkspaceRunStep step : recorded) {
            if (step.retryClass()
                    == com.specagent.runtime.execution.WorkspaceRunStepRetryClass.UNSAFE
                    && step.state() == WorkspaceRunStepState.IN_FLIGHT) {
                return RecoveryVerdict.unsafe(
                        "step " + step.stepKey() + " is IN_FLIGHT; the external call may have "
                                + "been issued but its result is not persisted",
                        base + " inFlightStep=" + step.stepKey()
                                + " originEpoch=" + step.originEpoch());
            }
        }

        LOG.info("Answer/Patch chain takeover accepted for run {}: verified {} recorded steps",
                runId, recorded.size());
        return RecoveryVerdict.safe(
                "verified " + recorded.size() + " persisted steps of the answer/patch chain; "
                        + "answers=" + shape.answerCount() + " patches=" + shape.patchCount());
    }

    /** 该适配器授权的 task_type 集合(P1-C 只支持 ANSWER_CYCLE)。 */
    public java.util.Set<String> supportedTaskTypes() {
        return SUPPORTED_TASK_TYPES;
    }

    /**
     * 文件名:AnswerChainFacts.java
     *
     * 用途:Answer/Patch 链核验所需的持久化事实读取端口。由画布侧实现,使适配器
     * 自己不依赖 answers / answer_patches 的仓储细节(保持公共调度层的依赖方向)。
     */
    public interface AnswerChainFacts {

        /**
         * 校验一个 SUCCEEDED 步骤的结果引用是否指向真实存在的行。
         *
         * @return null 表示引用有效;否则返回人类可读的悬空原因
         */
        String danglingReference(WorkspaceRunStep step);

        /** 该 Run 的 Answer/Patch 形状。 */
        ChainShape chainShape(UUID runId);
    }

    /**
     * Answer/Patch 链的形状。
     *
     * @param answerCount        该 Run 已落库的 Answer 数
     * @param patchCount         该 Run 已落库的 AnswerPatch 数
     * @param patchWithoutAnswer 存在 source answer 缺失的 patch
     */
    public record ChainShape(int answerCount, int patchCount, boolean patchWithoutAnswer) {
    }
}

package com.specagent.agent.runtime;

/**
 * 文件名:WorkspaceRunSteps.java
 *
 * 用途:画布周期步骤的稳定标识与结果引用约定(design §5.5)。
 *
 * <p>步骤身份<b>绝不包含 attempt</b>:接管后同一个逻辑步骤复用同一行,否则
 * 每次接管都会被识别成"新步骤",恢复就无法判断某个外部调用是否已经发生过。
 */
public final class WorkspaceRunSteps {

    private WorkspaceRunSteps() {
    }

    /** 上下文冻结:纯计算,安全重试。 */
    public static final String CONTEXT_BUILT = "context_built";

    /** 用户回答的不可变落库:由 answers 的唯一约束保证幂等,安全重试。 */
    public static final String ANSWER_PERSISTED = "answer_persisted";

    /** STATE_UPDATE 的模型调用:有计费副作用,不可安全重试。 */
    public static final String STATE_UPDATE_MODEL = "state_update_model";

    /** AnswerPatch checkpoint 落库:由唯一约束保证幂等,安全重试。 */
    public static final String PATCH_PERSISTED = "patch_persisted";

    /** DECISION 的模型调用:有计费副作用,不可安全重试。 */
    public static final String DECISION_MODEL = "decision_model";

    /** 提案产出节点的落库。 */
    public static final String NODE_PERSISTED = "node_persisted";

    /** 规格快照落库。 */
    public static final String SPEC_PERSISTED = "spec_persisted";

    /** 结果引用:回答。 */
    public static String answerRef(java.util.UUID answerId) {
        return "answer:" + answerId;
    }

    /** 结果引用:回答补丁。 */
    public static String patchRef(java.util.UUID patchId) {
        return "patch:" + patchId;
    }

    /** 结果引用:节点。 */
    public static String nodeRef(java.util.UUID nodeId) {
        return "node:" + nodeId;
    }

    /** 结果引用:规格快照。 */
    public static String specRef(java.util.UUID specSnapshotId) {
        return "spec:" + specSnapshotId;
    }

    /** 结果引用:上下文快照。 */
    public static String contextRef(java.util.UUID contextSnapshotId) {
        return "context:" + contextSnapshotId;
    }
}

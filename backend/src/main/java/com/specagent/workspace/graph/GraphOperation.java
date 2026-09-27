package com.specagent.workspace.graph;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:GraphOperation.java
 *
 * 用途:操作日志中的一条类型化、用户可见的持久图变更记录(只追加、
 * 不覆盖)。撤销就是针对这条日志做操作特定的补偿;不可变答案与历史
 * lineage 绝不会为了"让 UI 看起来回退了"而被物理删除。
 * {@code beforeRefs}/{@code afterRefs} 按操作类型携带补偿/重放所需的
 * 结构化状态。
 */
public class GraphOperation {

    public enum Actor { USER, AGENT, SYSTEM }

    public enum Status { ACTIVE, UNDONE }

    public enum Type {
        CREATE_DRAFT_NODE(true),
        EDIT_DRAFT_NODE(true),
        APPEND_CONTINUATION(true),
        CREATE_BRANCH_AND_APPEND(true),
        ATTACH_RESOURCE(true),
        /**
         * 一个悬浮(无路线)节点被接入路线 tip。撤销时是把它再摘下来
         * (节点仍然存在,只是脱离),而不是撤回它——用户的内容能安然
         * 度过一次"接入/撤销"循环。
         */
        CONNECT_FLOATING_NODE(true),
        /**
         * 一个节点被从其路线摘下,重新变成悬浮。节点内容永不被触碰;
         * 重做会把它重新接回原来的 tip。
         */
        DISCONNECT_NODE(true),
        CREATE_SEMANTIC_RELATION(true),
        SET_KNOWLEDGE_STATUS(true),
        ACCEPT_AGENT_PROPOSAL(false),
        /**
         * 从既有路线的某个历史节点 fork 出一条新分支路线(fork 共享来源
         * lineage,不复制任何东西)。撤销时软删除该 fork 路线(仅限它
         * 还未被续写时),并恢复之前的活跃路线指针。
         */
        ROUTE_FORK(true),
        /**
         * 创建了一条带克隆问题节点的重答路线。撤销时撤回克隆节点,
         * 并在路线仍停在克隆节点时软删除该路线。
         */
        ROUTE_REANSWER(true),
        /**
         * 一条替换(重新生成)路线被提交:替换问题节点已创建,来源路线
         * 已被取代。撤销时撤回替换节点,软删除其路线,并重新打开来源路线。
         */
        ROUTE_REGENERATE(true),
        /**
         * 一个悬浮的知识/资源节点开启了一条独立新路线。节点本身不动;
         * 撤销时在路线仍只包含该节点时软删除该路线。
         */
        ROUTE_START(true),
        /**
         * 一次显式的路线生命周期流转(归档 / 软删除 / 恢复)。撤销时应用
         * 逆向流转并恢复记录的活跃路线指针,严格遵循流转状态机,
         * 失败即拒绝。
         */
        ROUTE_LIFECYCLE(true);

        private final boolean reversibleByDefault;

        Type(boolean reversibleByDefault) {
            this.reversibleByDefault = reversibleByDefault;
        }

        public boolean reversibleByDefault() {
            return reversibleByDefault;
        }
    }

    private final UUID id;
    private final UUID projectId;
    private final Actor actor;
    private final Type type;
    private final List<UUID> targets;
    private final Map<String, Object> beforeRefs;
    private final Map<String, Object> afterRefs;
    private final String causedBy;
    private final boolean reversible;
    private final Status status;
    private final Instant createdAt;
    private final Instant undoneAt;

    public GraphOperation(UUID id,
                          UUID projectId,
                          Actor actor,
                          Type type,
                          List<UUID> targets,
                          Map<String, Object> beforeRefs,
                          Map<String, Object> afterRefs,
                          String causedBy,
                          boolean reversible,
                          Status status,
                          Instant createdAt,
                          Instant undoneAt) {
        this.id = id;
        this.projectId = projectId;
        this.actor = actor;
        this.type = type;
        this.targets = targets == null ? List.of() : List.copyOf(targets);
        this.beforeRefs = beforeRefs == null ? Map.of() : Map.copyOf(beforeRefs);
        this.afterRefs = afterRefs == null ? Map.of() : Map.copyOf(afterRefs);
        this.causedBy = causedBy;
        this.reversible = reversible;
        this.status = status;
        this.createdAt = createdAt;
        this.undoneAt = undoneAt;
    }

    public UUID id() {
        return id;
    }

    public UUID projectId() {
        return projectId;
    }

    public Actor actor() {
        return actor;
    }

    public Type type() {
        return type;
    }

    public List<UUID> targets() {
        return targets;
    }

    public Map<String, Object> beforeRefs() {
        return beforeRefs;
    }

    public Map<String, Object> afterRefs() {
        return afterRefs;
    }

    public String causedBy() {
        return causedBy;
    }

    public boolean reversible() {
        return reversible;
    }

    public Status status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant undoneAt() {
        return undoneAt;
    }

    public UUID targetNodeId() {
        return targets.isEmpty() ? null : targets.get(0);
    }
}

package com.specagent.workspace.graph;

import com.specagent.common.PreciseConflictException;

/**
 * 文件名:GraphRuleViolationException.java
 *
 * 用途:当图变更违反用户可以自行纠正的拓扑/状态规则时抛出
 * (依赖成环、在非 tip 节点上摘线/连线等)。异常携带稳定的 reason code,
 * 让 API 层能返回带产品文案的精确 409,而不是笼统的运行时冲突。
 *
 * 继承自 {@link PreciseConflictException}(它本身是
 * {@code IllegalStateException}):规则违反本质是状态前置条件失败,
 * 与图校验器沿用的 {@code IllegalStateException("<CODE>: ...")} 历史契约
 * 一致;API 层把这个具体类型映射为精确 409,
 * {@code CommandExecution} 会原样保留它,而不是降级为
 * {@code RUNTIME_CONFLICT}。
 */
public class GraphRuleViolationException extends PreciseConflictException {

    private final String code;

    public GraphRuleViolationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

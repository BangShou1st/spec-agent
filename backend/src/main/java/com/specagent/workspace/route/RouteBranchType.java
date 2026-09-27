package com.specagent.workspace.route;

/**
 * 文件名:RouteBranchType.java
 *
 * 用途:路线分叉类型的持久化枚举,记录一条路线从另一条路线岔开的
 * 原因(分叉/重新作答/重新生成/自由延伸)。
 */
public enum RouteBranchType {
    FORK,
    REANSWER,
    REGENERATE,
    /** 从非尾节点发起的自由延伸分支(free continuation branching)。 */
    CONTINUATION;

    public static RouteBranchType fromCode(String code) {
        return code == null ? null : valueOf(code.toUpperCase());
    }

    public String code() {
        return name().toLowerCase();
    }
}

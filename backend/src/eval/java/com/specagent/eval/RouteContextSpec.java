package com.specagent.eval;

/**
 * 文件名:RouteContextSpec.java
 *
 * 用途:声明回答周期在哪条路由上执行:活动路由末端(ACTIVE_TIP)或分叉
 * 路由末端(FORK_TIP)。{@code canonical()} 生成场景哈希用的规范化字符串。
 *
 * 协作:由 {@link GivenSpec} 持有,运行器据此准备执行上下文。
 */
public record RouteContextSpec(Kind kind, String routeStepRef) {

    public enum Kind {
        ACTIVE_TIP,
        FORK_TIP
    }

    public String canonical() {
        return "route(" + kind + "," + routeStepRef + ")";
    }
}

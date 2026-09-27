package com.specagent.workspace.route;

import com.specagent.workspace.node.Node;

/**
 * 文件名:RegenerateResult.java
 *
 * 用途:replacement(重新生成)操作的确定性结果,携带旧路线、
 * replacement 路线和 replacement 节点。旧路线会被标记为 superseded,
 * replacement 路线处于 OPEN 且激活状态。
 *
 * 历史上这里还携带过 {@code ContextSnapshot},但生产环境的 replacement
 * 流程从未填充它(始终为 {@code null})也从未读取;冻结的重新生成上下文
 * 由真正需要它的调用方构建(见 {@code ReplacementCycleService})。
 * 移除这个死字段同时切断了 route -&gt; context 的依赖边。
 */
public class RegenerateResult {

    private final Route oldRoute;
    private final Route replacementRoute;
    private final Node replacementNode;

    public RegenerateResult(Route oldRoute,
                            Route replacementRoute,
                            Node replacementNode) {
        this.oldRoute = oldRoute;
        this.replacementRoute = replacementRoute;
        this.replacementNode = replacementNode;
    }

    public Route oldRoute() {
        return oldRoute;
    }

    public Route replacementRoute() {
        return replacementRoute;
    }

    public Node replacementNode() {
        return replacementNode;
    }
}

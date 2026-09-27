package com.specagent.eval;

/**
 * 文件名:FocusContextSpec.java
 *
 * 用途:声明场景 {@code given} 块中的工作焦点(focus)放置:目标节点
 * 引用加说明。注意:焦点永远不会选中 Answer 节点。
 *
 * 协作:由 {@link GivenSpec} 持有,运行器据此设置初始工作焦点。
 */
public record FocusContextSpec(String focusStepRef, String note) {

    public String canonical() {
        return "focus(" + focusStepRef + "," + note + ")";
    }
}

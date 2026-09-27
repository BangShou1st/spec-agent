package com.specagent.model.contract;

/**
 * 文件名:FragmentListener.java
 *
 * 用途:一次流式补全调用期间接收提供商内容片段的监听器(汇聚口)。片段是按到达顺序
 * 排列的原始解码文本,不是 token,也不是 JSON 结构。回调返回 {@code false} 表示请求
 * 中止提供商流,传输层随后抛出 {@link StreamCancelledException}。监听器只能观察
 * 适合展示的文本,绝不能执行工具、导航或持久化操作;任何控制决策都必须等待完整且
 * 校验通过的契约结果。
 */
public interface FragmentListener {

    boolean onFragment(String fragment);
}
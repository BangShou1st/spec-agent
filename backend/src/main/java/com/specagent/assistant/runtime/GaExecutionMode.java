package com.specagent.assistant.runtime;

/**
 * 文件名:GaExecutionMode.java
 *
 * 用途:一次 GA 执行的能力模式。这里刻意把两件事分开:
 *
 * <ul>
 *   <li><b>模型选择</b>由用户决定——用户在模型设置里激活的 Provider 与 Model 是所有
 *       Agent 新 Run 的唯一权威来源;</li>
 *   <li><b>能力使用</b>由实际协议与已验证的模型能力决定——是否具备原生工具调用资格只
 *       决定能否启用 {@link #NATIVE_TOOLS},绝不决定能否普通聊天。</li>
 * </ul>
 *
 * <p>模式在 Run 准备阶段一次性确定并随执行绑定冻结;执行期间绝不自动切换,也绝不
 * 因为某次原生工具调用被上游拒绝而在同一个 Run 内暗中降级为纯文本。
 */
public enum GaExecutionMode {

    /**
     * 使用用户选中的模型执行普通文本对话:不发送 {@code tools},不发送
     * {@code tool_choice=required},不允许返回可执行 Tool Call,不生成伪造工具结果。
     * 模型自由文本中的任何"伪工具调用"都不会被执行。
     */
    TEXT_CHAT,

    /**
     * 使用同一个被选中的模型执行已经验证的原生工具调用:提交授权的 {@code tools},
     * 支持 {@code tool_calls} 与 assistant/tool 历史、工具结果回填,并保留既有
     * Tool Catalog、Policy、预算与安全围栏。
     */
    NATIVE_TOOLS;
}

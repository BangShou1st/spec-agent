package com.specagent.model.contract;

import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * 文件名:GaNativeInferenceGateway.java
 *
 * 用途:GA 专用的提供商无关原生模型执行入口。它只按<b>已冻结</b>的
 * {@link GaModelTarget}(提供商 + 模型 + 修订号 + 凭据)把一次原生工具调用请求
 * 交给对应提供商的原生传输,绝不读设置、绝不切换提供商或模型、绝不重试。
 *
 * <p>与 Project Agent 共用的只是"当前激活提供商"这一事实来源;GA 的原生请求形态
 * (tools / tool_calls / tool_call_id / 工具结果消息)与文本推理链路不同,因此不共用
 * 文本请求结构。
 *
 * <p>实现方必须在返回之前完成完整响应校验(含工具身份授权),并且:
 * <ul>
 *   <li>未注册原生传输的提供商返回 {@link UnsupportedOperationException}(宿主按
 *       {@code UNSUPPORTED_AGENT_MODEL} 处理),绝不用文本模拟工具调用;</li>
 *   <li>缺失终止信号、usage 或工具的响应一律失败关闭,绝不把部分参数当作可执行结果。</li>
 * </ul>
 */
public interface GaNativeInferenceGateway {

    /** 非流式原生调用;实现可以内部走提供商 SSE 再聚合(与已验证的路径一致)。 */
    GaModelContract.Response complete(GaModelTarget target, UUID conversationId,
                                      GaModelContract.Request request, Duration remainingBudget,
                                      BooleanSupplier active);

    /** 流式原生调用;候选文本按到达顺序送达监听器,最终结果仍是完整聚合后的契约。 */
    GaModelContract.Response stream(GaModelTarget target, UUID conversationId,
                                    GaModelContract.Request request, Duration remainingBudget,
                                    BooleanSupplier active, FragmentListener listener);
}

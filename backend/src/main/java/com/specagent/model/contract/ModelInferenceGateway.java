package com.specagent.model.contract;

import com.specagent.model.contract.FragmentListener;

/**
 * 文件名:ModelInferenceGateway.java
 *
 * 用途:所有模型调用方共享的底层提供商无关推理端口。旧的 {@code ModelGateway}
 * 负责渲染任务提示词并解析模型外层响应;本端口位于其下,只传递运行时已批准的
 * 消息。服务 Python 大脑的内部推理代理以及未来可能出现的 Java 侧调用方都共用
 * 此端口,从而避免提供商传输逻辑跨语言重复实现。
 *
 * 实现方必须在内部自行解析凭据且不得外泄;禁止在本端口背后追加重试或提供商
 * 降级(fallback)逻辑。
 */
public interface ModelInferenceGateway {

    ModelInferenceResponse complete(ModelInferenceRequest request);

    /**
     * {@link #complete(ModelInferenceRequest)} 的流式变体。提供商仍在生成内容时,
     * 内容片段会按到达顺序送达 {@code listener};最终返回的响应与 {@code complete}
     * 一样是完整聚合后的契约。控制面不变:调用方仍必须先校验完整响应才能据此行动。
     *
     * @throws com.specagent.model.contract.StreamCancelledException 当监听器拒绝内容时抛出
     */
    default ModelInferenceResponse completeStreaming(ModelInferenceRequest request,
            FragmentListener listener) {
        ModelInferenceResponse response = complete(request);
        if (response != null && response.content() != null && !response.content().isEmpty()) {
            if (!listener.onFragment(response.content())) {
                throw new com.specagent.model.contract.StreamCancelledException("listener declined content");
            }
        }
        return response;
    }
}

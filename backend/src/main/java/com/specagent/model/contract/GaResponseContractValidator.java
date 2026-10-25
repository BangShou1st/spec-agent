package com.specagent.model.contract;

import java.util.HashSet;
import java.util.Set;

/**
 * 文件名:GaResponseContractValidator.java
 *
 * 用途:GA 原生响应与请求的<b>协议无关契约校验</b>:tool_choice 语义、
 * 工具名在冻结 Catalog 内、绝不重放历史已出现过的 tool call id。
 *
 * <p>这三条不变量不属于任何单一供应商协议(Chat Completions / Responses /
 * Anthropic Messages 的已审计实现语义完全一致),因此收敛在契约层作为
 * 唯一实现:provider adapter 在各自 {@code validateResponse} 中委托本类,
 * {@code GaNativeModelBroker} 的权威复核也直接调用本类——同一份纯逻辑,
 * 允许在两个信任边界各执行一次,绝不放任何一方跳过。
 *
 * <p>协议专属的拒绝语义(Responses {@code function_call} 形状、Anthropic
 * {@code tool_use}/thinking blocks、text→tool_use 保真重放、Gemini
 * {@code thought_signature} 等)仍留在各自 adapter 的 {@code parse} 中,
 * 不进入本类。校验失败即失败关闭:拒绝释放任何可执行 ToolCall。
 */
public final class GaResponseContractValidator {

    private GaResponseContractValidator() {}

    /**
     * 校验响应与请求的契约一致性,违反即抛 {@link IllegalArgumentException}
     * (消息与既有三份实现保持一致,便于账本与错误映射继续按原文分类)。
     * 必须在释放可执行 ToolCall 之前完成。
     */
    public static GaModelContract.Response validate(GaModelContract.Request request,
            GaModelContract.Response response) {
        if (("none".equals(request.toolChoice()) && !response.toolCalls().isEmpty())
                || ("required".equals(request.toolChoice()) && response.toolCalls().isEmpty())) {
            throw new IllegalArgumentException("GA native response violates tool choice");
        }
        Set<String> names = new HashSet<>();
        request.tools().forEach(tool -> names.add(tool.name()));
        Set<String> priorIds = new HashSet<>();
        request.messages().stream()
                .flatMap(message -> message.toolCalls().stream())
                .forEach(call -> priorIds.add(call.id()));
        for (GaModelContract.ToolCall call : response.toolCalls()) {
            if (!names.contains(call.name()) || priorIds.contains(call.id())) {
                throw new IllegalArgumentException("GA native tool identity not authorized");
            }
        }
        return response;
    }
}

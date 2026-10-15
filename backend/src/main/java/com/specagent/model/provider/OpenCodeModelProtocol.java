package com.specagent.model.provider;

/**
 * 文件名:OpenCodeModelProtocol.java
 *
 * 用途:OpenCode Zen 模型的原生协议类型。
 *
 * <p>为什么需要它:OpenCode 同一个 API 基址下,不同模型走<b>不同的原生协议</b>
 * (官方模型目录元数据的 {@code provider.npm} 与官方 Console 文档的 Endpoint 列
 * 都明确标注)。Spec Agent 目前只实现 Chat Completions 调用链
 * ({@code POST /chat/completions}),因此"模型是哪个协议"必须被显式记录,绝不能
 * 把 Responses / Anthropic 模型当成 Chat 模型发出去——那必然得到上游 4xx,而前端
 * 却把它显示成可选模型。
 *
 * <p>判定来源是官方目录的 {@code provider.npm} 包名(与本地参考代理一致):
 * <ul>
 *   <li>{@code @ai-sdk/openai-compatible} → {@link #CHAT};</li>
 *   <li>{@code @ai-sdk/openai} → {@link #RESPONSES};</li>
 *   <li>含 {@code anthropic} → {@link #ANTHROPIC};</li>
 *   <li>其它已声明但本产品未实现的包(例如 {@code @ai-sdk/google})→ {@link #OTHER};</li>
 *   <li>缺失/未知 → {@link #UNKNOWN}(未验证,不得当作 Chat 执行)。</li>
 * </ul>
 */
public enum OpenCodeModelProtocol {

    CHAT,
    RESPONSES,
    ANTHROPIC,
    OTHER,
    UNKNOWN;

    /**
     * 由官方目录的 npm 包名推导协议。未知包名返回 {@link #UNKNOWN}——绝不猜测成
     * Chat,因为猜错会让请求打到不存在的端点。
     */
    public static OpenCodeModelProtocol fromNpmPackage(String npmPackage) {
        if (npmPackage == null || npmPackage.isBlank()) {
            return UNKNOWN;
        }
        String value = npmPackage.trim();
        if (value.contains("anthropic")) {
            return ANTHROPIC;
        }
        if ("@ai-sdk/openai".equals(value)) {
            return RESPONSES;
        }
        if (value.contains("openai-compatible")) {
            return CHAT;
        }
        return OTHER;
    }

    /** 本产品已实现的调用链是否覆盖该协议。 */
    public boolean chatExecutable() {
        return this == CHAT;
    }
}

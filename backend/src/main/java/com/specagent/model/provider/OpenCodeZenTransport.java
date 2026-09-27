package com.specagent.model.provider;

import com.specagent.model.contract.FragmentListener;
import com.specagent.model.contract.StreamCancelledException;

import java.util.List;

/**
 * 文件名:OpenCodeZenTransport.java
 *
 * 用途:OpenCode Zen 专属的 HTTP 协议边界。
 *
 * 持有 OpenCode Zen 的线上策略:base URL、User-Agent、bearer 授权和 JSON
 * content type。调用方绝不能再自行添加 User-Agent 头;传输层把它应用到每个
 * 请求上(包括凭据探测和模型列表请求),保证策略不会漂移。
 *
 * 传输层只做 HTTP:绝不读数据库、绝不解析凭据、绝不持久化任何数据。它把每种
 * 失败都映射为可诊断的 {@link OpenCodeModelException},且不泄漏 API key。
 */
public interface OpenCodeZenTransport {

    /**
     * OpenCode Zen API 的 base URL,例如 {@code https://opencode.ai/zen/v1}。
     */
    String BASE_URL = "https://opencode.ai/zen/v1";

    /**
     * 每个 HTTP 请求使用的 OpenCode 兼容客户端身份。这是该头的唯一定义;传输层
     * 把它统一应用于补全、模型列表和凭据探测请求。
     *
     * 经线上验证的完整形态:CLI(Bun fetch + @ai-sdk/provider-utils)会追加
     * sdk/runtime 后缀,且免费额度门禁要求 {@code opencode/} 前缀内的版本
     * >= 1.18.0(1.17 会得到 426)。保持完整的后缀链,让请求与真实 CLI 调用
     * 无法区分。
     */
    String USER_AGENT = "opencode/1.18.31 ai-sdk/provider-utils/4.0.23 runtime/bun/1.3.14";

    /**
     * 提供商会话关联头,随每个 Zen HTTP 请求由下方传输层独有的线上策略统一发送。
     */
    String SESSION_HEADER = "x-opencode-session";

    /**
     * 应用到每个 Zen HTTP 请求的桌面客户端身份头,让 OpenCode 把请求识别为来自
     * OpenCode 客户端(与已验证的 OpenCode 桌面应用发送的身份一致)。缺少完整
     * 集合时,OpenCode 会以 "free tier can only be used from within OpenCode"
     * 拒绝免费额度。这些头由传输层在每次请求时生成;调用方只需提供 run 会话
     * (SESSION_HEADER)。
     *
     * {@code x-opencode-project} 携带字面值 {@code global}:运行在非 git 全局
     * 配置下的 CLI 上报的项目 id 就是 {@code global},线上 A/B 已确认该值可被
     * 接受;而 {@code prj_} 形态的随机 id 未经过门禁验证。
     */
    String CLIENT_HEADER = "x-opencode-client";
    String REQUEST_HEADER = "x-opencode-request";
    String PROJECT_HEADER = "x-opencode-project";
    String CLIENT_ID = "cli";
    String GLOBAL_PROJECT = "global";

    /** 安全的端点来源信息;绝不包含授权值。 */
    default String endpoint() {
        return BASE_URL;
    }

    /**
     * 向 {@code POST /chat/completions} 发起一次 chat completion。
     *
     * @param apiKey    OpenCode bearer 凭据;不能为空白
     * @param sessionId 提供商会话,例如网关映射出的稳定 run 会话;必须非空白且为单行
     * @param request   最小化的 chat completion 载荷
     * @return 解析后的补全内容,以及可选的用量字段
     */
    OpenCodeCompletionResponse complete(String apiKey, String sessionId,
                                         OpenCodeChatCompletionRequest request);

    /**
     * {@link #complete(String, String, OpenCodeChatCompletionRequest)} 的流式变体。
     * 提供商仍在生成内容时,解码后的片段按到达顺序送达 {@code listener};最终
     * 返回的响应与完整聚合后的契约一致。监听器拒绝片段时,以
     * {@link StreamCancelledException} 中止流。
     */
    default OpenCodeCompletionResponse completeStreaming(String apiKey, String sessionId,
                                                          OpenCodeChatCompletionRequest request,
                                                          FragmentListener listener) {
        OpenCodeCompletionResponse response = complete(apiKey, sessionId, request);
        if (response != null && response.content() != null && !response.content().isEmpty()) {
            if (!listener.onFragment(response.content())) {
                throw new StreamCancelledException("listener declined content");
            }
        }
        return response;
    }

    /**
     * 向 {@code GET /models} 获取当前模型列表。
     *
     * @param apiKey 可选的 bearer 凭据;模型发现是公开接口,可为 null 或空白,
     *               但传输层仍会附加 OpenCode 的 User-Agent 策略
     */
    OpenCodeModelList listModels(String apiKey);

    /**
     * 发起有界的凭据探测(一次最小化补全),使存储的密钥可以在持久化之前先得到
     * 验证。与真实补全请求共用完全相同的传输层策略。
     *
     * @param apiKey 待验证的 OpenCode bearer 凭据;不能为空白
     * @param model  探测补全使用的模型;由调用方从当前模型列表中选择,绝不在此硬编码
     */
    void validateCredential(String apiKey, String model);
}

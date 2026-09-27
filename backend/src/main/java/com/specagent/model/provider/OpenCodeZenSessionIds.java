package com.specagent.model.provider;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 文件名:OpenCodeZenSessionIds.java
 *
 * 用途:OpenCode Zen 提供商侧的会话标识管理。纯传输层元数据:不是会话记忆、
 * 授权凭据、WorkingState、数据库字段或提示词上下文。一个会话(conversation)映射
 * 到一个稳定的 provider session,因此同一项目内的所有请求保持同一个提供商侧
 * 会话,而每次 HTTP 调用仍使用全新的 {@code msg_} 请求 id(见
 * {@link OpenCodeZenIds})。
 *
 * 头的取值必须是 Zen 的客户端形态:{@code ses_} 加 26 个字符(12 位十六进制
 * 时间前缀 + 14 位 base62)。UUID 形态的会话头会直接被 {@code FreeTierError}
 * 拒绝——这是与线上免费模型做单变量 A/B 对照得出的结论,因此 run id 绝不原样
 * 用作头的取值。
 */
public final class OpenCodeZenSessionIds {

    /**
     * 有上限的缓存,让同一会话复用同一个 provider session,而不是每次调用都
     * 呈现一个新会话。达到上限时整体清空缓存:会话只是关联元数据,丢失亲和性
     * 无害,而无界增长有害。
     */
    private static final int MAX_CACHED_RUNS = 8192;
    private static final ConcurrentMap<UUID, String> RUN_SESSIONS = new ConcurrentHashMap<>();

    private OpenCodeZenSessionIds() {
    }

    /**
     * 返回一个会话的稳定 session id。会话标识在调用方解析到所属项目时就是项目 ID,
     * 因此同一项目内的所有请求复用同一个提供商侧会话;标识为 null 时按失败处理。
     */
    public static String forConversation(UUID conversationId) {
        if (conversationId == null) {
            throw new IllegalArgumentException("Zen session conversation id must not be null");
        }
        String cached = RUN_SESSIONS.get(conversationId);
        if (cached != null) {
            return cached;
        }
        if (RUN_SESSIONS.size() >= MAX_CACHED_RUNS) {
            RUN_SESSIONS.clear();
        }
        String generated = OpenCodeZenIds.sessionId();
        String existing = RUN_SESSIONS.putIfAbsent(conversationId, generated);
        return existing == null ? generated : existing;
    }

    /**
     * 无 run 请求(凭据探测、模型发现)使用的一次性 session id。
     */
    public static String newEphemeral() {
        return OpenCodeZenIds.sessionId();
    }
}

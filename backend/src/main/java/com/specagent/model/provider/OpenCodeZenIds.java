package com.specagent.model.provider;

import com.sharedlibs.opencodezen.OpenCodeZenIdGenerator;

/**
 * 文件名:OpenCodeZenIds.java
 *
 * 用途:OpenCode Zen 的线上标识符生成。
 *
 * Zen 只对标识符头看起来像真实客户端的请求开放免费额度,而会话头有冻结的
 * 形态:{@code x-opencode-session} 必须是 {@code ses_} 加 26 个字符——12 个
 * 小写十六进制字符的时间前缀,后接 14 个 base62 字符。以下结论是与线上免费模型
 * 做单变量 A/B 对照隔离出来的:
 *
 * - {@code ses_} + 32 位 UUID  → 403 {@code FreeTierError}
 * - {@code ses_} + 26 位带时间前缀的 id → 200
 * - 缺少该头 → 403
 * - 时间前缀内<b>计数器必须是小值(≤ 0xF)</b>:上游按真实客户端的"同一毫秒内
 *   从 0 单调递增"的计数器语义校验降序会话 id 的中段十六进制(取反后落在
 *   {@code 0xff0..0xfff})。随机计数器只有 16/4096 的概率合规——2026-10-16 的
 *   字节级 A/B 复现证实:除计数器外与成功代理逐字节一致的请求,随机计数器
 *   稳定得到 403 {@code FreeTierError},真实小值计数器稳定 200。
 *
 * 因此计数器由本类持有:同一毫秒内单调递增,跨毫秒重置,与真实客户端一致。
 * 前缀其余部分编码毫秒时间戳(乘以 {@code 0x1000} 后加计数器),可按位取反以
 * 得到降序 id;后缀是随机的。顺序字母表式 id(abcdefg...)会被上游的低熵
 * 黑名单拒绝;这种"时间戳 + 单调计数器 + 随机后缀"的方案永远不会产生它们。
 */
final class OpenCodeZenIds {

    private OpenCodeZenIds() {
    }

    /** {@code ses_} 会话 id;同一 run 内稳定不变,前缀为降序。 */
    static String sessionId() {
        return OpenCodeZenIdGenerator.newSessionId();
    }

    /** {@code msg_} 请求 id;每个 HTTP 请求生成一个新值,前缀为升序。 */
    static String requestId() {
        return OpenCodeZenIdGenerator.newRequestId();
    }
}

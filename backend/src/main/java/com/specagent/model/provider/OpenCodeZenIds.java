package com.specagent.model.provider;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

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
 *
 * 前缀编码了一个毫秒时间戳(乘以 {@code 0x1000} 后加一个 12 位计数器),
 * 可按位取反以得到降序 id;后缀是随机的。该算法与已验证的客户端保持一致,因此
 * 生成的 id 与真实 id 无法区分。顺序字母表式 id(abcdefg...)会被上游的低熵
 * 黑名单拒绝;这种"时间戳 + 随机"的方案永远不会产生它们。
 */
final class OpenCodeZenIds {

    private static final String BASE62 =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int ID_LENGTH = 26;
    private static final int PREFIX_HEX_LENGTH = 12;

    private OpenCodeZenIds() {
    }

    /** {@code ses_} 会话 id;同一 run 内稳定不变,前缀为降序。 */
    static String sessionId() {
        return "ses_" + generate(true);
    }

    /** {@code msg_} 请求 id;每个 HTTP 请求生成一个新值,前缀为升序。 */
    static String requestId() {
        return "msg_" + generate(false);
    }

    private static String generate(boolean descending) {
        long timestamp = System.currentTimeMillis();
        long current = timestamp * 0x1000L + ThreadLocalRandom.current().nextInt(0x1000);
        long value = descending ? ~current : current;
        StringBuilder id = new StringBuilder(ID_LENGTH);
        for (int i = 0; i < PREFIX_HEX_LENGTH / 2; i++) {
            id.append(String.format(Locale.ROOT, "%02x", (int) ((value >> (40 - 8 * i)) & 0xFF)));
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = PREFIX_HEX_LENGTH; i < ID_LENGTH; i++) {
            id.append(BASE62.charAt(random.nextInt(BASE62.length())));
        }
        return id.toString();
    }
}

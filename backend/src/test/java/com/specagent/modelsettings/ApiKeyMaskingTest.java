package com.specagent.modelsettings;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件名:ApiKeyMaskingTest.java
 *
 * 测试目标:统一脱敏原语的边界语义——短密钥(≤4)原文绝不作为后缀出现、
 * null/空白视为无密钥、null 安全等值。这是 MS-CROSS-R1 短密钥防泄漏
 * 语义的唯一实现,四个设置服务全部委托本类。
 */
class ApiKeyMaskingTest {

    @Test
    void suffixNeverReturnsShortKeyVerbatim() {
        assertNull(ApiKeyMasking.suffix(null));
        assertNull(ApiKeyMasking.suffix(""));
        assertNull(ApiKeyMasking.suffix("a"));
        assertNull(ApiKeyMasking.suffix("ab"));
        assertNull(ApiKeyMasking.suffix("abcd"));
    }

    @Test
    void suffixReturnsLastFourOfLongKeys() {
        assertEquals("bcde", ApiKeyMasking.suffix("abcde"));
        assertEquals("wxyz", ApiKeyMasking.suffix("very-long-key-wxyz"));
    }

    @Test
    void hasKeyTreatsNullAndBlankAsAbsent() {
        assertFalse(ApiKeyMasking.hasKey(null));
        assertFalse(ApiKeyMasking.hasKey(""));
        assertFalse(ApiKeyMasking.hasKey("   "));
        assertTrue(ApiKeyMasking.hasKey("k"));
    }

    @Test
    void equalsNullableIsNullSafe() {
        assertTrue(ApiKeyMasking.equalsNullable(null, null));
        assertFalse(ApiKeyMasking.equalsNullable(null, "a"));
        assertFalse(ApiKeyMasking.equalsNullable("a", null));
        assertTrue(ApiKeyMasking.equalsNullable("a", "a"));
        assertFalse(ApiKeyMasking.equalsNullable("a", "b"));
    }
}

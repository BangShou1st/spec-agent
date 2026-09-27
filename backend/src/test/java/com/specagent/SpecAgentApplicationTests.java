package com.specagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 文件名:SpecAgentApplicationTests.java
 *
 * 测试目标:验证在 test profile 下 Spring 应用上下文能够正常启动加载,
 * 作为最基本的冒烟测试。
 */
@SpringBootTest
@ActiveProfiles("test")
class SpecAgentApplicationTests {

    @Test
    void contextLoads() {
        // 验证 test profile 下 Spring 应用上下文能成功加载
    }
}
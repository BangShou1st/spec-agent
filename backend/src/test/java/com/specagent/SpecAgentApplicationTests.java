package com.specagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class SpecAgentApplicationTests {

    @Test
    void contextLoads() {
        // Verifies Spring application context loads successfully with test profile
    }
}
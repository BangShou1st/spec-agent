package com.specagent.assistant;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
/**
 * 文件名:ProbeAdviceTest.java
 *
 * 测试目标:验证 SSE 的错误状态契约——Accept 为 event-stream 时无法
 * 协商出 JSON 错误体,所以 SSE 端点对未知运行返回裸 404,
 * 而 JSON 读端点仍保留其类型化错误体。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProbeAdviceTest {
    @Autowired MockMvc mockMvc;
    @Test
    void unknownRunJsonGives404() throws Exception {
        mockMvc.perform(get("/api/v1/global-assistant/runs/" + UUID.randomUUID())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }
    @Test
    void unknownRunEventStreamGives404() throws Exception {
        mockMvc.perform(get("/api/v1/global-assistant/runs/" + UUID.randomUUID() + "/events")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNotFound());
    }
}

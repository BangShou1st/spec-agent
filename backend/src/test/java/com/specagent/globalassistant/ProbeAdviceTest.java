package com.specagent.globalassistant;
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
 * SSE error-status contract: JSON error bodies cannot be negotiated for an
 * event-stream Accept, so the SSE endpoint answers unknown runs with a bare
 * 404 while the JSON read endpoints keep their typed error bodies.
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

package com.specagent.agent.broker;

import com.specagent.agent.broker.ModelInferenceHttpRequest;
import com.specagent.agent.contract.AgentProtocol;
import com.specagent.agent.runevent.AgentRunEventRepository;
import com.specagent.agent.runtime.RunService;
import com.specagent.common.Json;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Internal inference broker safety: shared-secret auth, closed call types,
 * bounded prompts, sanitized events, and no credential material anywhere in
 * responses. Uses the deterministic fake inference gateway (test profile).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InternalModelInferenceBrokerIntegrationTest {

    private static final String TOKEN = "dev-internal-secret";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private RunService runService;
    @Autowired
    private AgentRunEventRepository eventRepository;
    @Autowired
    private Json json;

    private String body(UUID runId, String callType, int promptChars) {
        ModelInferenceHttpRequest request = new ModelInferenceHttpRequest(
                AgentProtocol.INFERENCE_PROTOCOL_VERSION,
                runId,
                callType,
                List.of(new ModelInferenceHttpRequest.Message("system", "s"),
                        new ModelInferenceHttpRequest.Message("user", "x".repeat(promptChars))),
                2048);
        return json.write(request);
    }

    @Test
    void rejectsMissingOrWrongInternalToken() throws Exception {
        UUID runId = newRun();
        mockMvc.perform(post("/internal/v1/model-inference")
                        .contentType("application/json")
                        .content(body(runId, "STATE_UPDATE", 10)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/v1/model-inference")
                        .header(AgentProtocol.INTERNAL_TOKEN_HEADER, "wrong")
                        .contentType("application/json")
                        .content(body(runId, "STATE_UPDATE", 10)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void completesWithFakeGatewayAndRecordsSanitizedEvent() throws Exception {
        UUID runId = newRun();
        String response = mockMvc.perform(post("/internal/v1/model-inference")
                        .header(AgentProtocol.INTERNAL_TOKEN_HEADER, TOKEN)
                        .contentType("application/json")
                        .content(body(runId, "DECISION", 10)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(response)
                .contains("\"protocolVersion\":\"model-inference.v1\"")
                .contains("REQUEST_USER_INPUT")
                // No credential material ever crosses the broker boundary.
                .doesNotContain("apiKey")
                .doesNotContain("sk-");

        String events = eventText(runId);
        assertThat(events).contains("MODEL_INFERENCE");
        assertThat(events).contains("promptSha256");
        // Events are sanitized: raw prompt content is never recorded.
        assertThat(events).doesNotContain("xxxxxxxxxx");
    }

    @Test
    void rejectsNonExistentRunId() throws Exception {
        UUID fabricatedRunId = UUID.randomUUID();
        mockMvc.perform(post("/internal/v1/model-inference")
                        .header(AgentProtocol.INTERNAL_TOKEN_HEADER, TOKEN)
                        .contentType("application/json")
                        .content(body(fabricatedRunId, "STATE_UPDATE", 10)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnknownCallTypeAndOversizedPrompt() throws Exception {
        UUID runId = newRun();
        mockMvc.perform(post("/internal/v1/model-inference")
                        .header(AgentProtocol.INTERNAL_TOKEN_HEADER, TOKEN)
                        .contentType("application/json")
                        .content(body(runId, "HACK", 10)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/internal/v1/model-inference")
                        .header(AgentProtocol.INTERNAL_TOKEN_HEADER, TOKEN)
                        .contentType("application/json")
                        .content(body(runId, "STATE_UPDATE", 300_000)))
                .andExpect(status().isBadRequest());
    }

    private UUID newRun() {
        Project project = projectService.createProject("Broker 测试项目");
        return runService.createQueuedRun(project.id()).id();
    }

    private String eventText(UUID runId) {
        StringBuilder text = new StringBuilder();
        eventRepository.findByRunId(runId)
                .forEach(event -> text.append(event.eventType()).append(' ')
                        .append(event.payload().toString()).append('\n'));
        return text.toString();
    }
}

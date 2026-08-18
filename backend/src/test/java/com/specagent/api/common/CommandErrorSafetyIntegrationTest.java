package com.specagent.api.common;

import com.specagent.agent.FakeModelAdapter;
import com.specagent.agent.ModelRequest;
import com.specagent.model.gateway.ModelGatewayErrorCategory;
import com.specagent.model.gateway.ModelGatewayException;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Command-oriented error safety tests. Simulated gateway failures map to
 * provider-neutral codes and static messages; raw provider payloads, test
 * sentinels, credentials, and stack traces never reach the client, and the
 * failed run stays diagnosable through its safe trace steps.
 *
 * <p>Deliberately not {@code @Transactional}: the failure phase records the
 * FAILED run through a {@code REQUIRES_NEW} transaction that must observe the
 * persisted run row.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CommandErrorSafetyIntegrationTest {

    private static final String SECRET_SENTINEL = "sk-error-safety-secret-5d0c";
    private static final String RAW_PAYLOAD_SENTINEL = "raw-provider-body-9f17";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;

    @SpyBean
    private FakeModelAdapter fakeModelAdapter;

    @Test
    void rateLimitedGatewayMapsToSafeProviderNeutralError() throws Exception {
        doAnswer(invocation -> {
            throw new ModelGatewayException(ModelGatewayErrorCategory.RATE_LIMITED,
                    RAW_PAYLOAD_SENTINEL + " " + SECRET_SENTINEL);
        }).when(fakeModelAdapter).run(any(ModelRequest.class));
        Project project = projectService.createProject("Gateway rate limit");

        MvcResult result = mockMvc.perform(post("/api/v1/projects/{projectId}/questions/next", project.id()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("MODEL_PROVIDER_RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("The model provider is temporarily rate limited"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain(SECRET_SENTINEL)
                .doesNotContain(RAW_PAYLOAD_SENTINEL)
                .doesNotContain("Bearer")
                .doesNotContain("sk-")
                .doesNotContain("at com.specagent")
                .doesNotContain("Exception");
    }

    @Test
    void connectionFailureMapsToUnreachableWithoutTransportDetails() throws Exception {
        doAnswer(invocation -> {
            throw new ModelGatewayException(ModelGatewayErrorCategory.CONNECTION,
                    "connect to 10.0.0.7:443 failed: " + SECRET_SENTINEL);
        }).when(fakeModelAdapter).run(any(ModelRequest.class));
        Project project = projectService.createProject("Gateway connection");

        MvcResult result = mockMvc.perform(post("/api/v1/projects/{projectId}/questions/next", project.id()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("MODEL_PROVIDER_UNREACHABLE"))
                .andExpect(jsonPath("$.message").value("The model provider could not be reached"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain(SECRET_SENTINEL)
                .doesNotContain("10.0.0.7")
                .doesNotContain("443");
    }

    @Test
    void failedRunKeepsSafeTraceStepsAndNoRawFailureText() throws Exception {
        doAnswer(invocation -> {
            throw new ModelGatewayException(ModelGatewayErrorCategory.SERVER_ERROR,
                    "500 " + RAW_PAYLOAD_SENTINEL);
        }).when(fakeModelAdapter).run(any(ModelRequest.class));
        Project project = projectService.createProject("Gateway failure trace");

        mockMvc.perform(post("/api/v1/projects/{projectId}/questions/next", project.id()))
                .andExpect(status().isBadGateway());

        MvcResult result = mockMvc.perform(get("/api/v1/projects/{projectId}/runs", project.id()))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .contains("failed:provider:SERVER_ERROR")
                .contains("model_called:DRAFT_NODE")
                .doesNotContain(RAW_PAYLOAD_SENTINEL)
                .doesNotContain(SECRET_SENTINEL);
    }

    @Test
    void notConfiguredGatewayMapsToServiceUnavailable() throws Exception {
        doAnswer(invocation -> {
            throw new ModelGatewayException(ModelGatewayErrorCategory.NOT_CONFIGURED,
                    "gateway has no configured credential");
        }).when(fakeModelAdapter).run(any(ModelRequest.class));
        Project project = projectService.createProject("Gateway not configured");

        mockMvc.perform(post("/api/v1/projects/{projectId}/questions/next", project.id()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MODEL_PROVIDER_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.message").value("The model provider is not configured"));
    }
}
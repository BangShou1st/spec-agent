package com.specagent.model.gateway;

import com.specagent.agent.AgentAction;
import com.specagent.agent.AgentTaskType;
import com.specagent.agent.ModelRequest;
import com.specagent.agent.ModelResponse;
import com.specagent.credential.CredentialStatus;
import com.specagent.credential.OpenCodeCredentialService;
import com.specagent.model.provider.OpenCodeModelCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicit manual live smoke against the real OpenCode Zen API.
 *
 * <p>This test is gated on the {@code SPEC_AGENT_OPENCODE_KEY} environment
 * variable and never runs under {@code gradlew test} by default: automated
 * tests must make zero public OpenCode requests. Run it explicitly, e.g.:
 *
 * <pre>
 *   SPEC_AGENT_MODEL_GATEWAY=opencode SPEC_AGENT_OPENCODE_KEY=... \
 *   SPEC_AGENT_OPENCODE_MODEL=... gradlew.bat test \
 *       --tests "com.specagent.model.gateway.OpenCodeZenLiveSmokeTest"
 * </pre>
 *
 * <p>The {@code SPEC_AGENT_MODEL_GATEWAY=opencode} switch is required: the
 * smoke proves the real runtime wiring resolves the OpenCode gateway through
 * the normal ModelGateway selection, not by autowiring the class directly.
 *
 * <p>The real key is only seeded into the encrypted credential store and never
 * printed; the transaction rolls back afterwards so the key is not left in the
 * development database.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@EnabledIfEnvironmentVariable(named = "SPEC_AGENT_OPENCODE_KEY", matches = ".+")
class OpenCodeZenLiveSmokeTest {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private OpenCodeCredentialService credentialService;
    @Autowired
    private OpenCodeModelCatalog catalog;
    @Autowired
    private OpenCodeZenModelGateway gateway;

    @Test
    void liveSmokeSeedsCredentialDiscoversFreeModelsAndCompletes() {
        System.out.println("=== OpenCodeZenLiveSmokeTest: explicit live smoke (public OpenCode allowed) ===");
        String apiKey = System.getenv("SPEC_AGENT_OPENCODE_KEY");

        // 0. The runtime must actually resolve the OpenCode gateway through the
        // normal ModelGateway selection, not through a direct autowire.
        assertThat(context.getBean(ModelGateway.class)).isInstanceOf(OpenCodeZenModelGateway.class);
        System.out.println("gateway selector: opencode -> OpenCodeZenModelGateway");

        // 1. Seed the credential through the encrypted credential service (the
        // probe model is chosen dynamically from the live free model list).
        CredentialStatus status = credentialService.save(apiKey);
        assertThat(status.configured()).isTrue();
        assertThat(status.masked()).isEqualTo("••••" + apiKey.substring(apiKey.length() - 4));
        assertThat(status.masked()).doesNotContain(apiKey);
        System.out.println("credential configured: yes, masked: " + status.masked());

        // 2. Resolve the credential back through the runtime service.
        String resolved = credentialService.resolveOpenCode().orElseThrow();
        assertThat(resolved).isEqualTo(apiKey);
        System.out.println("credential resolved for OpenCode gateway: yes");

        // 3. Discover current free models from GET /models.
        List<String> freeModels = catalog.listFreeModels(resolved);
        assertThat(freeModels).isNotEmpty();
        assertThat(freeModels).allMatch(id -> id.endsWith("-free"));
        System.out.println("/models request: PASS; free models discovered: " + freeModels.size());

        // 4. The explicitly selected model must be among the current free models.
        String selected = System.getenv().getOrDefault("SPEC_AGENT_OPENCODE_MODEL", "mimo-v2.5-free");
        assertThat(freeModels).contains(selected);
        System.out.println("selected free model: " + selected);

        // 5. Run one real chat completion through the gateway; the action must
        // come from the model's own envelope output.
        ModelRequest request = new ModelRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                AgentTaskType.DRAFT_NODE, "{}", Map.of());
        ModelResponse response = gateway.run(request);
        assertThat(response.requestAgentRunId()).isEqualTo(request.agentRunId());
        assertThat(response.requestContextSnapshotId()).isEqualTo(request.contextSnapshotId());
        assertThat(response.taskType()).isEqualTo(AgentTaskType.DRAFT_NODE);
        assertThat(response.action()).isEqualTo(AgentAction.ASK_NEXT_QUESTION);
        assertThat(response.outputJson()).isNotBlank();
        System.out.println("chat completion: PASS; model: " + selected
                + "; task: " + response.taskType().code()
                + "; action: " + response.action().code()
                + "; output length: " + response.outputJson().length());
    }
}
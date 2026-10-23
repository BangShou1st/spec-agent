package com.specagent.modelsettings;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** L4: select an API-created connection in the explicitly isolated test database. */
@SpringBootTest @ActiveProfiles("test") @Tag("external-service")
@EnabledIfEnvironmentVariable(named="SPEC_AGENT_LIVE_CONNECTION_TEST",matches="true")
class ModelConnectionLiveQualificationTest {
    @Autowired ModelProvidersService connections;
    @Autowired ModelConnectionCapabilities capabilities;
    @Test void qualifyExactConfiguredConnectionUsingOnlyExplicitlyFreeModel() {
        assertThat(System.getenv("SPEC_AGENT_LIVE_MAX_REQUESTS")).isEqualTo("4");
        UUID id=UUID.fromString(System.getenv("SPEC_AGENT_LIVE_CONNECTION_ID"));
        String model=System.getenv("SPEC_AGENT_LIVE_MODEL");
        var row=connections.require(id);
        assertThat(row.selectedModel()).isEqualTo(model);
        assertThat(row.apiFormat()).isEqualTo("CHAT_COMPLETIONS");
        // A directory GET consumes no inference request. Unknown cost stops here;
        // never infer free from a name, never choose another model or retry.
        assertThat(connections.listModels(id).freeModels()).contains(model);
        var verified=connections.validate(id);
        assertThat(verified.configRevision()).isEqualTo(row.configRevision());
        assertThat(capabilities.status(verified)).containsEntry("CHAT","VERIFIED")
                .containsEntry("STRUCTURED","VERIFIED").containsEntry("NATIVE_TOOLS","VERIFIED");
    }
}

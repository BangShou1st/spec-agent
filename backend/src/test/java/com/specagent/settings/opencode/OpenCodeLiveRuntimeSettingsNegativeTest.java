package com.specagent.settings.opencode;

import com.specagent.model.inference.ModelInferenceGateway;
import com.specagent.model.inference.OpenCodeModelInferenceGateway;
import com.specagent.model.provider.OpenCodeModelException;
import com.specagent.testing.FakeModelInferenceGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/** Missing eval credentials/model must fail before any DB lookup or fallback. */
@SpringBootTest(properties = {
        "spec.agent.model.inference=opencode",
        "spec.agent.model.runtime-settings-source=external-environment",
        "spec.agent.model.external.api-key=",
        "spec.agent.model.external.selected-model=",
        "spec.agent.model.opencode.base-url=https://opencode.ai/zen/v1"
})
@ActiveProfiles("test")
class OpenCodeLiveRuntimeSettingsNegativeTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private OpenCodeSettingsService service;

    @Autowired
    private ModelInferenceGateway inferenceGateway;

    @MockBean
    private OpenCodeSettingsRepository repository;

    @Test
    void missingLiveConfigurationFailsClosedWithoutTestDatabaseOrFakeFallback() {
        assertThat(inferenceGateway).isInstanceOf(OpenCodeModelInferenceGateway.class);
        assertThat(context.getBeansOfType(FakeModelInferenceGateway.class)).isEmpty();

        assertThatThrownBy(service::requireRuntimeSettings)
                .isInstanceOf(OpenCodeModelException.class)
                .hasMessageContaining("Live OpenCode provider configuration is missing")
                .hasMessageContaining("SPEC_AGENT_EVAL_OPENCODE_KEY")
                .hasMessageContaining("SPEC_AGENT_EVAL_OPENCODE_MODEL")
                .hasMessageContaining("test database settings are not used")
                .doesNotHaveToString("beta-free");
        verifyNoInteractions(repository);
    }
}

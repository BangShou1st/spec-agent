package com.specagent.access;

import com.specagent.runtime.execution.RuntimeExecutionSettings;
import java.util.Map;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.UrlResource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;

class DeploymentPropertyBindingTest {
    private MockEnvironment environment(Map<String, Object> variables, boolean testProfile) throws Exception {
        var environment = new MockEnvironment();
        var loader = new YamlPropertySourceLoader();
        if (testProfile) for (var source : loader.load("test", new ClassPathResource("application-test.yml")))
            environment.getPropertySources().addLast(source);
        for (var source : loader.load("main", new ClassPathResource("application.yml")))
            environment.getPropertySources().addLast(source);
        environment.getPropertySources().addFirst(new MapPropertySource("deployment", variables));
        return environment;
    }

    @Test void oidcEnvironmentVariablesReachTheActualJavaBindingPrefix() throws Exception {
        var environment = environment(Map.of("SPEC_AGENT_SECURITY_MODE", "OIDC",
                "SPEC_AGENT_SECURITY_ISSUER_URI", "https://identity.example/realm"), false);
        var properties = Binder.get(environment).bind("spec.agent.security", Bindable.of(IdentityModeProperties.class)).get();
        assertThat(properties.accessMode()).isEqualTo(AccessMode.OIDC);
        assertThat(environment.getProperty("spec.agent.security.mode")).isEqualTo("OIDC");
        assertThat(properties.getIssuerUri()).isEqualTo("https://identity.example/realm");
        assertThat(properties.isModeAdmissionEnabled()).isTrue();
    }

    @Test void workerSwitchAndCapacityEnvironmentVariablesReachRuntimeSettings() throws Exception {
        var environment = environment(Map.of("SPEC_AGENT_MULTI_WORKER_ENABLED", "true",
                "SPEC_AGENT_EXECUTION_LOCAL_SLOTS", "7", "SPEC_AGENT_EXECUTION_LEASE_SECONDS", "77"), false);
        var settings = Binder.get(environment).bind("spec.agent.execution", Bindable.of(RuntimeExecutionSettings.class)).get();
        assertThat(environment.getProperty("spec.agent.execution.multi-worker-enabled", Boolean.class)).isTrue();
        assertThat(settings.getLocalSlots()).isEqualTo(7);
        assertThat(settings.getLeaseSeconds()).isEqualTo(77);
        assertThat(environment.getProperty("spec.execution.multi-worker-enabled")).isNull();
    }

    @Test void localTestProfileUsesTheSameJavaBindingPrefix() throws Exception {
        var environment = environment(Map.of(), true);
        var properties = Binder.get(environment).bind("spec.agent.security", Bindable.of(IdentityModeProperties.class)).get();
        assertThat(properties.accessMode()).isEqualTo(AccessMode.LOCAL);
        assertThat(properties.isModeAdmissionEnabled()).isTrue();
        assertThat(environment.getProperty("spec.security.mode")).isNull();
    }

    @Test void everyPackagedAndTestProfileCopyUsesTheCorrectPrefix() throws Exception {
        var resources = Collections.list(getClass().getClassLoader().getResources("application-test.yml"));
        assertThat(resources).hasSizeGreaterThanOrEqualTo(2);
        var loader = new YamlPropertySourceLoader();
        for (var resource : resources) {
            var environment = new MockEnvironment();
            for (var source : loader.load(resource.toString(), new UrlResource(resource)))
                environment.getPropertySources().addLast(source);
            assertThat(environment.getProperty("spec.agent.security.mode")).isEqualTo("LOCAL");
            assertThat(environment.getProperty("spec.security.mode")).isNull();
        }
    }
}

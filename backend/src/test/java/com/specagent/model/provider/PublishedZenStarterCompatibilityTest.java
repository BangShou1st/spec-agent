package com.specagent.model.provider;

import com.sharedlibs.opencodezen.OpenCodeZenProtocolPolicy;
import com.sharedlibs.opencodezen.autoconfigure.OpenCodeZenAutoConfiguration;
import com.sharedlibs.opencodezen.autoconfigure.OpenCodeZenRequestFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the published starter's real auto-configuration without HTTP or a datasource. */
class PublishedZenStarterCompatibilityTest {
    @Test
    void publishedStarterAutoConfiguresTheFactoryAndBuildsRequestsWithoutNetwork() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OpenCodeZenAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(OpenCodeZenRequestFactory.class);
                    var factory = context.getBean(OpenCodeZenRequestFactory.class);
                    var request = factory.newConversationRequest(
                            URI.create("https://example.invalid/chat/completions"), true, null,
                            UUID.fromString("00000000-0000-0000-0000-000000000001")).build();
                    assertThat(request.headers().firstValue("Authorization")).contains("Bearer public");
                    assertThat(request.headers().firstValue("User-Agent"))
                            .contains(OpenCodeZenProtocolPolicy.USER_AGENT);
                    assertThat(request.headers().firstValue("x-opencode-session"))
                            .isEqualTo(request.headers().firstValue("x-opencode-session-id"));
                    assertThat(OpenCodeZenRequestFactory.class.getProtectionDomain()
                            .getCodeSource().getLocation().toString())
                            .endsWith("opencode-zen-spring-boot-autoconfigure-v1.4.2.jar");
                });
    }
}

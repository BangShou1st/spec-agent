package com.specagent.modelsettings;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

/** Read-only provider catalog through the existing secure service, never an inference or settings write. */
@SpringBootTest
@ActiveProfiles("test")
@org.junit.jupiter.api.Tag("external-service")
class RagD4R1GeminiDiscoveryTest {
    static {
        // Existing local provider-test proxy, isolated to this opted-in JVM.
        if("true".equals(System.getenv("SPEC_AGENT_GEMINI_R1_DISCOVERY"))) {
            System.setProperty("https.proxyHost", "127.0.0.1");
            System.setProperty("https.proxyPort", "7897");
            System.setProperty("http.nonProxyHosts", "localhost|127.0.0.1");
        }
    }
    @Autowired CustomProviderSettingsService service;
    @Test void discoverWithoutChangingActiveSettingsOrMakingAnInference() throws Exception {
        assertThat(System.getenv("SPEC_AGENT_GEMINI_R1_DISCOVERY")).as("explicit catalog-only opt-in").isEqualTo("true");
        String desktop=System.getenv("SPEC_AGENT_GEMINI_R1_DESKTOP");
        Path file=(desktop==null?Path.of(System.getProperty("user.home"),"Desktop"):Path.of(desktop)).resolve("key2.txt");
        assertThat(Files.isRegularFile(file)).as("Gemini credential file available").isTrue();
        String key=Files.readString(file).strip();
        // Opaque provider credentials may evolve; retain the complete value.
        // The authenticated catalog, not a hard-coded prefix, proves usability.
        assertThat(!key.isBlank() && key.length()<=16384 && !key.contains("\n") && !key.contains("\r"))
            .as("credential is one bounded opaque value").isTrue();
        try {
            var discovery=service.discover("CHAT_COMPLETIONS","https://generativelanguage.googleapis.com/v1beta/openai",key);
            var models=discovery.models().stream().filter(m->m.contains("flash-lite")).toList();
            System.out.println("Gemini credential available: "+!models.isEmpty());
            System.out.println("GEMINI_R1_CATALOG_MODELS "+String.join(",",models));
            assertThat(models.stream().map(m -> m.replaceFirst("^models/", "")).toList())
                .contains("gemini-3.5-flash-lite");
        } catch(RuntimeException failure) {
            System.out.println("Gemini credential available: false");
            if(failure instanceof com.specagent.model.contract.ModelGatewayException gateway)
                System.out.println("GEMINI_R1_CATALOG_FAILURE "+gateway.gatewayCategory()+" status="+gateway.httpStatus());
            fail("Gemini authenticated catalog unavailable (details withheld)");
        }
    }
}

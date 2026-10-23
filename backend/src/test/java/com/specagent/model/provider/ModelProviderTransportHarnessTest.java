package com.specagent.model.provider;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.*;
import com.specagent.support.FakeModelProvider;
import com.sharedlibs.kilogateway.autoconfigure.KiloGatewayProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** One behavioral contract against each production transport, including both shared libraries. */
class ModelProviderTransportHarnessTest {
    Function<GaModelContract.Request,GaModelContract.Response> transport(String provider,FakeModelProvider fake) {
        String model="fixture-model";
        var target=new GaModelTarget(provider,model,"fixture-revision","fixture-secret");
        return switch(provider) {
            case "OPENCODE_ZEN" -> {
                var zen=new HttpOpenCodeZenTransport(new ObjectMapper(),fake.baseUrl(),5,"DIRECT");
                yield request -> zen.completeNativeGa(target.credential(),"ses_0123456789abABCDEFGHIJKLMN",
                        request,model,Duration.ofSeconds(10),()->true);
            }
            case "KILO" -> {
                var properties=new KiloGatewayProperties();properties.setBaseUrl(fake.baseUrl());
                properties.setConnectTimeout(Duration.ofSeconds(5));properties.setFreeOnly(false);
                var kilo=new KiloGaNativeTransport(properties);
                yield request -> kilo.complete(target,request,Duration.ofSeconds(10),()->true);
            }
            case "CUSTOM" -> {
                var settings=new RuntimeCustomSettings("CHAT_COMPLETIONS",fake.baseUrl(),"fixture-secret",
                        model,1,UUID.randomUUID(),true);
                var custom=new ChatCompletionsGaTextTransport(()->settings,new ObjectMapper(),HttpClient.newHttpClient());
                var connection=new GaModelTarget(provider,model,settings.bindingRevision(),settings.apiKey());
                yield request -> custom.complete(connection,request,Duration.ofSeconds(10),()->true);
            }
            default -> throw new IllegalArgumentException();
        };
    }
    @ParameterizedTest @ValueSource(strings={"OPENCODE_ZEN","KILO","CUSTOM"})
    void nativeToolRoundTripIsIdenticalAcrossAllProductionTransports(String provider) throws Exception {
        try(var fake=new FakeModelProvider()) {
            new NativeToolRoundTripVerifier().verify(transport(provider,fake));
            assertThat(fake.requests).hasSize(2);
            assertThat(fake.keys).containsOnly("Bearer fixture-secret");
            var history=fake.requests.get(1).path("messages");
            assertThat(history.get(1).path("tool_calls").get(0).path("id").asText()).isEqualTo("fixture-call");
            assertThat(history.get(2).path("tool_call_id").asText()).isEqualTo("fixture-call");
            assertThat(fake.requests).allSatisfy(r->assertThat(r.path("model").asText()).isEqualTo("fixture-model"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"OPENCODE_ZEN","KILO","CUSTOM"})
    void unrecognizedToolCannotObtainQualification(String provider) throws Exception {
        try(var fake=new FakeModelProvider()) {
            fake.badCall=true;
            assertThatThrownBy(()->new NativeToolRoundTripVerifier().verify(transport(provider,fake)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(fake.requests).hasSize(1);
        }
    }
    @ParameterizedTest @ValueSource(strings={"OPENCODE_ZEN","KILO","CUSTOM"})
    void rateLimitNeverRetriesOrLeaksCredentials(String provider) throws Exception {
        try(var fake=new FakeModelProvider()) {
            fake.status=429;
            assertThatThrownBy(()->new NativeToolRoundTripVerifier().verify(transport(provider,fake)))
                    .isInstanceOf(ModelGatewayException.class).hasMessageNotContaining("fixture-secret");
            assertThat(fake.requests).hasSize(1);
        }
    }
    @ParameterizedTest @ValueSource(strings={"OPENCODE_ZEN","KILO"})
    void missingSseTerminatorFailsClosed(String provider) throws Exception {
        try(var fake=new FakeModelProvider()) {
            fake.missingDone=true;
            assertThatThrownBy(()->new NativeToolRoundTripVerifier().verify(transport(provider,fake)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(fake.requests).hasSize(1);
        }
    }
}

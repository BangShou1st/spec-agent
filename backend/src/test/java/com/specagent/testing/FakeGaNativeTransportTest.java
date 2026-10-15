package com.specagent.testing;

import com.specagent.model.contract.*;
import com.specagent.model.provider.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FakeGaNativeTransportTest {
    private final HttpOpenCodeZenTransport http = mock(HttpOpenCodeZenTransport.class);
    private GaModelContract.Request request() {
        return new GaModelContract.Request(GaModelContract.VERSION,UUID.randomUUID(),1,UUID.randomUUID(),UUID.randomUUID(),
            "AGENT",UUID.randomUUID(),List.of(new GaModelContract.Message("user","hello",List.of(),null)),List.of(),"auto",128,true);
    }
    private ApplicationContextRunner context(boolean testProfile) {
        return new ApplicationContextRunner().withBean(HttpOpenCodeZenTransport.class, () -> http)
            .withUserConfiguration(FakeGaNativeTransport.class)
            .withInitializer(ctx -> { if(testProfile) ctx.getEnvironment().setActiveProfiles("test"); });
    }
    @Test void neverInstalledWithoutExplicitFlag() {
        context(true).run(ctx -> assertThat(ctx).doesNotHaveBean(FakeGaNativeTransport.class));
    }
    @Test void neverInstalledOutsideTestProfileEvenWithFlag() {
        context(false).withPropertyValues("spec.agent.testing.ga-native-transport=fake")
            .run(ctx -> assertThat(ctx).doesNotHaveBean(FakeGaNativeTransport.class));
    }
    @Test void explicitFixtureBecomesNativeTransportOnlyInTestProfile() {
        context(true).withPropertyValues("spec.agent.testing.ga-native-transport=fake")
            .run(ctx -> assertThat(ctx.getBean(OpenCodeZenTransport.class)).isInstanceOf(FakeGaNativeTransport.class));
    }
    @Test void nativeFragmentsAggregateWithoutProviderCalls() {
        var nativeTransport = new FakeGaNativeTransport(http,0);
        var text = new StringBuilder();
        var response = nativeTransport.streamNativeGa("dummy","session",request(),"test",Duration.ofSeconds(1),()->true,
            part -> {text.append(part);return true;});
        assertThat(response.content()).isEqualTo(text.toString());
        assertThat(response.toolCalls()).isEmpty();
        assertThat(response.finishReason()).isEqualTo("stop");
        verifyNoInteractions(http);
    }
    @Test void revocationStopsBeforeAnotherFragmentAndNoProviderIsCalled() {
        var fragments = new AtomicInteger();
        assertThatThrownBy(() -> new FakeGaNativeTransport(http,0).streamNativeGa("dummy","session",request(),"test",Duration.ofSeconds(1),
            () -> fragments.get()==0, part -> { fragments.incrementAndGet();return true; })).isInstanceOf(StreamCancelledException.class);
        assertThat(fragments.get()).isEqualTo(1);verifyNoInteractions(http);
    }
    @Test void declinedListenerAndExpiredDeadlineFailClosed() {
        var transport = new FakeGaNativeTransport(http,0);
        assertThatThrownBy(() -> transport.streamNativeGa("dummy","session",request(),"test",Duration.ofSeconds(1),()->true,part->false))
            .isInstanceOf(StreamCancelledException.class);
        assertThatThrownBy(() -> transport.completeNativeGa("dummy","session",request(),"test",Duration.ZERO,()->true))
            .isInstanceOf(StreamCancelledException.class);verifyNoInteractions(http);
    }
    @Test void ordinarySettingsTransportStillDelegates() {
        new FakeGaNativeTransport(http,0).listModels("dummy");verify(http).listModels("dummy");
    }
}

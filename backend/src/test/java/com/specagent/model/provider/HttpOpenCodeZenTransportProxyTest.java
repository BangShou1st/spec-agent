package com.specagent.model.provider;

import org.junit.jupiter.api.Test;
import java.net.http.HttpClient;
import static org.assertj.core.api.Assertions.assertThat;

class HttpOpenCodeZenTransportProxyTest {

    @Test
    void defaultIsDirect() {
        assertThat(HttpOpenCodeZenTransport.proxySelectorFor(null)).isSameAs(HttpClient.Builder.NO_PROXY);
        assertThat(HttpOpenCodeZenTransport.proxySelectorFor("")).isSameAs(HttpClient.Builder.NO_PROXY);
        assertThat(HttpOpenCodeZenTransport.proxySelectorFor("DIRECT")).isSameAs(HttpClient.Builder.NO_PROXY);
        assertThat(HttpOpenCodeZenTransport.proxySelectorFor("direct")).isSameAs(HttpClient.Builder.NO_PROXY);
    }

    @Test
    void explicitProxyIsUsed() {
        var sel = HttpOpenCodeZenTransport.proxySelectorFor("http://127.0.0.1:7897");
        assertThat(sel).isNotSameAs(HttpClient.Builder.NO_PROXY);
    }

    @Test
    void invalidFallsBackToDirect() {
        assertThat(HttpOpenCodeZenTransport.proxySelectorFor("not-a-proxy")).isSameAs(HttpClient.Builder.NO_PROXY);
        assertThat(HttpOpenCodeZenTransport.proxySelectorFor("http://:0")).isSameAs(HttpClient.Builder.NO_PROXY);
    }
}

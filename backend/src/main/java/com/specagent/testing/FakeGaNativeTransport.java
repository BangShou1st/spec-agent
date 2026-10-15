package com.specagent.testing;

import com.specagent.model.contract.FragmentListener;
import com.specagent.model.contract.GaModelContract;
import com.specagent.model.contract.StreamCancelledException;
import com.specagent.model.provider.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Explicit local acceptance fixture. Broker authorization/ledger remain outside this transport.
 * Ordinary settings/model transport delegates unchanged; native GA alone is deterministic.
 * Never selects itself on provider errors, and never manufactures tool calls.
 */
@Component
@Primary
@Profile("test")
@ConditionalOnProperty(name="spec.agent.testing.ga-native-transport", havingValue="fake")
public class FakeGaNativeTransport implements OpenCodeZenTransport {
    private final HttpOpenCodeZenTransport delegate;
    private final long fragmentDelayMs;
    public FakeGaNativeTransport(HttpOpenCodeZenTransport delegate,
            @Value("${spec.agent.testing.ga-native-fragment-delay-ms:1000}") long delay) {
        if (delay < 0 || delay > 10000) throw new IllegalArgumentException("Invalid native fixture delay");
        this.delegate = delegate; this.fragmentDelayMs = delay;
    }
    @Override public OpenCodeCompletionResponse complete(String key,String session,OpenCodeChatCompletionRequest request) {
        return delegate.complete(key,session,request);
    }
    @Override public OpenCodeCompletionResponse completeStreaming(String key,String session,OpenCodeChatCompletionRequest request,FragmentListener listener) {
        return delegate.completeStreaming(key,session,request,listener);
    }
    @Override public OpenCodeModelList listModels(String key) { return delegate.listModels(key); }
    @Override public void validateCredential(String key,String model) { delegate.validateCredential(key,model); }
    @Override public String endpoint() { return delegate.endpoint(); }
    @Override public GaModelContract.Response completeNativeGa(String key,String session,GaModelContract.Request request,
            String model,Duration remaining,BooleanSupplier active) {
        return streamNativeGa(key,session,request,model,remaining,active,part -> true);
    }
    @Override public GaModelContract.Response streamNativeGa(String key,String session,GaModelContract.Request request,
            String model,Duration remaining,BooleanSupplier active,FragmentListener listener) {
        if ("required".equals(request.toolChoice())) throw new UnsupportedOperationException("Fixture does not fabricate tool calls");
        if (remaining.isNegative() || remaining.isZero()) throw new StreamCancelledException("fixture deadline");
        Instant deadline = Instant.now().plus(remaining);
        List<String> parts = List.of("这是原生流验收替身。", "权限与预算由真实宿主校验。", "此响应不代表真实模型能力。");
        StringBuilder content = new StringBuilder();
        for (String part : parts) {
            check(active,deadline);
            long waitUntil = System.nanoTime() + fragmentDelayMs * 1_000_000;
            while (System.nanoTime() < waitUntil) {
                check(active,deadline);
                try { Thread.sleep(Math.min(25, Math.max(1,(waitUntil-System.nanoTime())/1_000_000))); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new StreamCancelledException("fixture interrupted");
                }
            }
            check(active,deadline);
            if (!listener.onFragment(part)) throw new StreamCancelledException("fixture listener declined");
            content.append(part);
        }
        check(active,deadline);
        return new GaModelContract.Response(GaModelContract.VERSION, content.toString(), List.of(), "stop",
                new GaModelContract.Usage(1,1));
    }
    private void check(BooleanSupplier active,Instant deadline) {
        if (!active.getAsBoolean() || !Instant.now().isBefore(deadline)) throw new StreamCancelledException("fixture inactive/deadline");
    }
}

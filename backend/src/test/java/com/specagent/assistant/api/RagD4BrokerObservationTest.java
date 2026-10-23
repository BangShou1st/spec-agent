package com.specagent.assistant.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.specagent.access.ActorContext;
import com.specagent.access.ActorSource;
import com.specagent.assistant.runtime.*;
import com.specagent.assistant.tool.*;
import com.specagent.capability.*;
import com.specagent.common.Hashes;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real broker serialization, mocked ledger/runtime; never opens a datasource or calls a model. */
class RagD4BrokerObservationTest {
    @Test void capabilityWarningsSurviveIntoTheActualModelToolEnvelope() {
        var executions=mock(GaExecutionStore.class);
        var runtime=mock(CapabilityRuntime.class);
        var catalogs=spy(new GaCatalogProjection(mock(GlobalAssistantCatalogService.class)));
        var descriptor=new GaCatalogProjection.Descriptor("project.content.discover","1","project_content_discover","test",
                Map.of("query",Map.of("type","string","required",true)),true,"NONE");
        var catalog=catalogs.snapshot(List.of(descriptor));
        doReturn(catalog).when(catalogs).current();
        when(executions.catalog(any())).thenReturn(catalog);
        when(executions.requireActor(any())).thenReturn(new ActorContext(UUID.randomUUID(),UUID.randomUUID(),ActorSource.LOCAL));
        when(executions.recordedTool(any(),anyString(),anyString(),anyString())).thenReturn(true);
        when(executions.reserve(any(),any(),eq("TOOL"),anyString())).thenReturn(new GaExecutionStore.Reservation(true,"RESERVED",null));
        when(executions.performReservedTool(any(),any(),any())).thenAnswer(call -> call.<Supplier<String>>getArgument(2).get());
        when(runtime.invokeApplicationScoped(anyString(),eq(descriptor.capabilityId()),any(),anyMap())).thenReturn(
            new CapabilityResult(UUID.randomUUID(),"d4",descriptor.capabilityId(),CapabilityResult.Status.SUCCEEDED,
                Map.of("candidates",List.of(),"vectorUnavailable",true),List.of(),Map.of(),List.of("PROJECT_INDEX_NOT_READY")));
        var broker=new GaCapabilityBroker(executions,catalogs,runtime,mock(GlobalAssistantRunEventService.class),mock(GlobalAssistantUiActionValidator.class));
        Map<String,Object> args=Map.of("query","synthetic");
        String body=catalogs.canonical(Map.of("protocolVersion","ga-capability-invocation.v1","runId",UUID.randomUUID(),
            "executionEpoch",1,"leaseId",UUID.randomUUID(),"toolCallId","d4-call","capabilityId",descriptor.capabilityId(),
            "descriptorVersion","1","catalogHash",catalog.hash(),"arguments",args,"argumentsHash",Hashes.sha256Hex(catalogs.canonical(args))));
        var response=broker.invoke(body);
        assertThat(response.status()).isEqualTo(200);
        var result=(JsonNode)response.body();
        assertThat(result.path("status").textValue()).isEqualTo("SUCCEEDED");
        assertThat(result.path("content").path("warnings").get(0).textValue()).isEqualTo("PROJECT_INDEX_NOT_READY");
        assertThat(result.path("content").path("vectorUnavailable").booleanValue()).isTrue();
        assertThat(result.path("sourceRefs").isEmpty()).isTrue();
        verify(runtime,times(1)).invokeApplicationScoped(anyString(),anyString(),any(),anyMap());
    }
}

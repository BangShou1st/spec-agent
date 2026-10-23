package com.specagent.model.provider;

import com.specagent.model.contract.GaModelContract;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Shared capability experiment. The supplied transport owns all wire details. */
public final class NativeToolRoundTripVerifier {
    public void verify(Function<GaModelContract.Request, GaModelContract.Response> inference) {
        var tool = new GaModelContract.Tool("probe_echo", "Return the supplied text",
                Map.of("type", "object", "properties", Map.of("text",Map.of("type","string")),
                        "required",List.of("text"),"additionalProperties",false));
        var messages = new ArrayList<GaModelContract.Message>();
        messages.add(new GaModelContract.Message("user",
                "Call probe_echo with text probe ok, then answer after receiving the result.",null,null));
        var request = request(messages,List.of(tool),"required");
        var adapter = new GaChatCompletionsAdapter();
        var response = adapter.validateResponse(request,inference.apply(request));
        if (response.toolCalls().size()!=1) fail("One tool call required");
        var call=response.toolCalls().getFirst();
        if (!"probe_echo".equals(call.name()) || !call.arguments().equals(Map.of("text","probe ok"))) {
            fail("Unexpected probe arguments");
        }
        messages.add(new GaModelContract.Message("assistant",response.content(),response.toolCalls(),null));
        messages.add(new GaModelContract.Message("tool","probe ok",null,call.id()));
        var followup=request(messages,List.of(tool),"none");
        var answer=adapter.validateResponse(followup,inference.apply(followup));
        if (!answer.toolCalls().isEmpty() || answer.content().isBlank()) fail("Final text answer required");
    }
    private GaModelContract.Request request(List<GaModelContract.Message> messages,List<GaModelContract.Tool> tools,String choice) {
        return new GaModelContract.Request(GaModelContract.VERSION,UUID.randomUUID(),1,UUID.randomUUID(),
                UUID.randomUUID(),"AGENT",UUID.randomUUID(),messages,tools,choice,256,false);
    }
    private void fail(String reason) { throw ModelProviderException.invalidResponse("capability-probe",reason); }
}

package com.specagent.model.provider;

import com.specagent.model.contract.StreamCancelledException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GaNativeSseDecoderTest {
    static final String CALL = """
            data: {"choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"private","tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"project_search","arguments":"{\\"query\\":"}}]},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"中文\\"}"}}]},"finish_reason":"tool_calls"}]}

            data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":4}}

            data: [DONE]

            """;

    private static com.specagent.model.contract.GaModelContract.Response decode(String stream) {
        return new GaNativeSseDecoder().decode(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)), () -> true);
    }

    @Test void assemblesSplitArgumentsAndDiscardsReasoning() {
        var response = decode(CALL);
        assertEquals("c1", response.toolCalls().getFirst().id());
        assertEquals("中文", response.toolCalls().getFirst().arguments().get("query"));
        assertFalse(response.toString().contains("private"));
    }

    @Test void requiresDoneFinishAndUsage() {
        for (String stream : java.util.List.of(CALL.replace("data: [DONE]\n\n", ""),
                CALL.replace("\"finish_reason\":\"tool_calls\"", "\"finish_reason\":null"),
                CALL.replace("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":4}}\n\n", ""),
                CALL.replace("\"finish_reason\":\"tool_calls\"", "\"finish_reason\":\"length\""))) {
            assertThrows(IllegalArgumentException.class, () -> decode(stream));
        }
    }

    @Test void rejectsParallelCallsMalformedArgumentsAndPostFinishData() {
        assertThrows(IllegalArgumentException.class, () -> decode(CALL.replace("\"index\":0,\"function\"", "\"index\":1,\"function\"")));
        assertThrows(IllegalArgumentException.class, () -> decode(CALL.replace("中文", "中文\\\\\"")));
        assertThrows(IllegalArgumentException.class, () -> decode(CALL.replace("data: [DONE]", "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"late\"}}]}\n\ndata: [DONE]")));
    }

    @Test void interleavedNativeCallsPreserveIndexesArgumentsAndEmitNoProtocolAsBody() {
        String stream="""
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"first","type":"function","function":{"name":"help_search","arguments":"{\\"query\\":\\""}},{"index":1,"id":"second","type":"function","function":{"name":"project_search","arguments":"{\\"query\\":\\""}}]},"finish_reason":null}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"arguments":"第二个\\"}"}},{"index":0,"function":{"arguments":"第一个\\"}"}}]},"finish_reason":"tool_calls"}]}

            data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":4}}

            data: [DONE]

            """;
        var body=new java.util.ArrayList<String>();
        var result=new GaNativeSseDecoder().decode(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),()->true,text->{body.add(text);return true;});
        assertEquals(java.util.List.of("first","second"),result.toolCalls().stream().map(c->c.id()).toList());
        assertEquals("第一个",result.toolCalls().get(0).arguments().get("query"));
        assertEquals("第二个",result.toolCalls().get(1).arguments().get("query")); assertTrue(body.isEmpty());
        assertThrows(IllegalArgumentException.class,()->decode(stream.replace("\"index\":1","\"index\":5")));
        assertThrows(IllegalArgumentException.class,()->decode(stream.replace("\"id\":\"second\"","\"id\":\"first\"")));
        assertThrows(IllegalArgumentException.class,()->decode(stream.replace("\"index\":1","\"index\":18446744073709551617")));
    }

    @Test void rejectsDuplicateKeysWithoutEchoingProviderContents() {
        var error = assertThrows(IllegalArgumentException.class, () -> decode(CALL.replace("\"role\":\"assistant\"", "\"role\":\"assistant\",\"role\":\"private\"")));
        assertFalse(error.getMessage().contains("private"));
    }

    @Test void boundsStreamAndRejectsInvalidUtf8() {
        assertThrows(IllegalArgumentException.class, () -> decode(":" + "x".repeat(1048577)));
        assertThrows(IllegalArgumentException.class, () -> new GaNativeSseDecoder().decode(
                new ByteArrayInputStream(new byte[]{(byte) 0xc3, 0x28}), () -> true));
    }

    @Test void cancellationRemainsCancellation() {
        assertThrows(StreamCancelledException.class, () -> new GaNativeSseDecoder().decode(
                new ByteArrayInputStream(CALL.getBytes(StandardCharsets.UTF_8)), () -> false));
    }
    @Test void candidateCallbackNeverReceivesArgumentsReasoningOrRejectedTail() {
        var seen=new java.util.ArrayList<String>();
        new GaNativeSseDecoder().decode(new ByteArrayInputStream(CALL.getBytes(StandardCharsets.UTF_8)),
                () -> true,text -> { seen.add(text); return true; });
        assertTrue(seen.isEmpty());
        String prefix="data: {\"choices\":[{\"delta\":{\"content\":\"先查一下\"},\"finish_reason\":null}]}\n\n";
        var parsed=new GaNativeSseDecoder().decode(new ByteArrayInputStream((prefix+CALL).getBytes(StandardCharsets.UTF_8)),
                () -> true,text -> { seen.add(text); return true; });
        assertEquals(java.util.List.of("先查一下"),seen);
        assertFalse(parsed.toolCalls().isEmpty());
        assertThrows(StreamCancelledException.class,()->new GaNativeSseDecoder().decode(
                new ByteArrayInputStream((prefix+CALL).getBytes(StandardCharsets.UTF_8)),()->true,text->false));
    }

    @Test void acceptsZenTerminalUsageChoiceWithOnlyEmptyFields() {
        String tail = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":null},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":4}}";
        String stream = CALL.replace("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":4}}", tail);
        assertEquals("c1", decode(stream).toolCalls().getFirst().id());
        assertThrows(IllegalArgumentException.class, () -> decode(stream.replace("\"content\":null,\"tool_calls\":null", "\"content\":\"late\",\"tool_calls\":null")));
    }
}

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

    /**
     * deepseek 系列(Zen)先在带 finish_reason 的帧上报 usage,随后以 choices:[] 尾帧
     * 重复一次完全相同的 usage。线上实测 5/5 两帧逐字段相同。必须接受,否则整条
     * GA 原生路径对该模型系列确定性失败(GA_NATIVE_STREAM_USAGE)。
     */
    @Test void acceptsDuplicateIdenticalUsageAcrossFinishAndTailFrames() {
        String usage = "{\"prompt_tokens\":403,\"completion_tokens\":64,\"total_tokens\":467,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":0},"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":19}}";
        var response = decode(deepSeekStream(usage, usage));
        assertEquals("c1", response.toolCalls().getFirst().id());
        assertEquals("中文", response.toolCalls().getFirst().arguments().get("query"));
        assertEquals(403, response.usage().promptTokens());
        assertEquals(64, response.usage().completionTokens());
    }

    /** 放宽的是重复次数,绝不是内容:被改写的第二次 usage 仍是协议违规。 */
    @Test void rejectsRewrittenUsageInTailFrame() {
        String usage = "{\"prompt_tokens\":403,\"completion_tokens\":64}";
        assertThrows(IllegalArgumentException.class,
                () -> decode(deepSeekStream(usage, "{\"prompt_tokens\":403,\"completion_tokens\":65}")));
    }

    /** 缺失 usage 仍然失败:放宽重复不代表放弃 usage 强制。 */
    @Test void stillRequiresUsageOnDeepSeekShape() {
        assertThrows(IllegalArgumentException.class, () -> decode(deepSeekStream(null, null)));
    }

    /**
     * 部分 Zen 模型(big-pickle、ll-alpha-free 等)在后续分片里显式回填 {@code role:null},
     * 按 OpenAI 规范表达"本帧不携带 role"。null 必须与字段缺失同义地接受,
     * 否则整条 GA 原生路径对这些模型确定性失败(GA_NATIVE_STREAM_DELTA)。
     */
    @Test void acceptsExplicitNullRoleAndNullToolFieldsInLaterFragments() {
        String stream = """
            data: {"choices":[{"index":0,"finish_reason":null,"delta":{"role":"assistant","content":""}}]}

            data: {"choices":[{"index":0,"finish_reason":null,"delta":{"role":null,"content":null,"reasoning_content":"想","tool_calls":null}}]}

            data: {"choices":[{"index":0,"finish_reason":null,"delta":{"role":null,"content":null,"reasoning_content":null,"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"project_search","arguments":"{\\"query\\":\\"中文\\"}"}}]}}]}

            data: {"choices":[{"index":0,"finish_reason":"tool_calls","delta":{"reasoning_content":null}}],"usage":{"prompt_tokens":235,"completion_tokens":77}}

            data: [DONE]

            """;
        var response = decode(stream);
        assertEquals("c1", response.toolCalls().getFirst().id());
        assertEquals("中文", response.toolCalls().getFirst().arguments().get("query"));
        assertEquals(235, response.usage().promptTokens());
    }

    /** 非 null 且非 assistant 的 role 仍是违规:放宽的只是 null 这一种"未携带"写法。 */
    @Test void rejectsNonAssistantRoleInFragment() {
        String stream = """
            data: {"choices":[{"index":0,"finish_reason":null,"delta":{"role":"user","content":"hi"}}]}

            data: {"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":1}}

            data: [DONE]

            """;
        assertThrows(IllegalArgumentException.class, () -> decode(stream));
    }

    /**
     * deepseek 系列线上实测帧序:带 tool_calls 的帧不带 finish_reason;finish 帧同时
     * 携带 finish_reason 与 usage;随后是 choices:[] 尾帧再次携带 usage。
     */
    private static String deepSeekStream(String finishUsage, String tailUsage) {
        String finish = finishUsage == null ? "" : ",\"usage\":" + finishUsage;
        String tail = tailUsage == null ? "" : "\n\ndata: {\"choices\":[],\"usage\":" + tailUsage + "}\n";
        return """
            data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"","reasoning_content":""}}],"usage":null}

            data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"","tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"project_search","arguments":"{\\"query\\":\\"中文\\"}"}}]}}],"usage":null}

            data: {"choices":[{"index":0,"finish_reason":"tool_calls","delta":{"role":"assistant","content":"","reasoning_content":""}}]%s}
            %s
            data: [DONE]

            """.formatted(finish, tail);
    }
}

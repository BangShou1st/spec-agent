package com.specagent.model.contract;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 文件名:GaResponseContractValidatorTest.java
 *
 * 测试目标:GA 原生响应契约校验的唯一实现——tool_choice 一致性、
 * 工具名授权、tool call id 不重放。这三个不变量此前分散在三个协议
 * adapter 中各自实现且两份无任何测试覆盖;收敛到契约层后由本测试
 * 直接锁定,任何协议路径(含 Broker 权威复核)都委托同一份逻辑。
 */
class GaResponseContractValidatorTest {

    private static GaModelContract.Request request() throws Exception {
        return GaModelContract.readRequest(Files.readString(
                Path.of("../contracts/global-assistant/fixtures/ga-model-request-valid.json")));
    }

    private static GaModelContract.Response responseWith(GaModelContract.ToolCall... calls) {
        return new GaModelContract.Response(GaModelContract.VERSION, "",
                List.of(calls), "tool_calls", new GaModelContract.Usage(1, 1));
    }

    @Test
    void catalogToolCallIsAccepted() throws Exception {
        var request = request();
        var call = new GaModelContract.ToolCall("fresh-1", "project_search", Map.of("query", "test"));
        var response = assertDoesNotThrow(() ->
                GaResponseContractValidator.validate(request, responseWith(call)));
        assertEquals(response, GaResponseContractValidator.validate(request, response));
    }

    @Test
    void toolChoiceNoneRejectsAnyToolCall() throws Exception {
        var request = request();
        var noneChoice = new GaModelContract.Request(request.protocolVersion(), request.runId(),
                request.executionEpoch(), request.leaseId(), request.callId(), request.callType(),
                request.modelBindingId(), request.messages(), request.tools(), "none",
                request.maxOutputTokens(), request.stream());
        var call = new GaModelContract.ToolCall("fresh-1", "project_search", Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> GaResponseContractValidator.validate(noneChoice, responseWith(call)));
    }

    @Test
    void toolChoiceRequiredRejectsEmptyToolCalls() throws Exception {
        var request = request();
        var requiredChoice = new GaModelContract.Request(request.protocolVersion(), request.runId(),
                request.executionEpoch(), request.leaseId(), request.callId(), request.callType(),
                request.modelBindingId(), request.messages(), request.tools(), "required",
                request.maxOutputTokens(), request.stream());
        var empty = new GaModelContract.Response(GaModelContract.VERSION, "text only",
                List.of(), "stop", new GaModelContract.Usage(1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> GaResponseContractValidator.validate(requiredChoice, empty));
    }

    @Test
    void reservedProviderToolNamesCannotReachTheHost() throws Exception {
        var request = request();
        for (String reserved : List.of("bash", "read", "write", "project_search_typo")) {
            var call = new GaModelContract.ToolCall("fresh-1", reserved, Map.of());
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> GaResponseContractValidator.validate(request, responseWith(call)),
                    "tool " + reserved + " must be rejected");
            assertEquals("GA native tool identity not authorized", ex.getMessage());
        }
    }

    @Test
    void replayedHistoricalCallIdIsRejected() throws Exception {
        var request = request();
        // ga-model-request-valid.json 的 assistant 历史里已有 native-1
        var replay = new GaModelContract.ToolCall("native-1", "project_search", Map.of("query", "x"));
        var ex = assertThrows(IllegalArgumentException.class,
                () -> GaResponseContractValidator.validate(request, responseWith(replay)));
        assertEquals("GA native tool identity not authorized", ex.getMessage());
    }

    @Test
    void toolChoiceViolationKeepsItsDistinctMessage() throws Exception {
        var request = request();
        var noneChoice = new GaModelContract.Request(request.protocolVersion(), request.runId(),
                request.executionEpoch(), request.leaseId(), request.callId(), request.callType(),
                request.modelBindingId(), request.messages(), request.tools(), "none",
                request.maxOutputTokens(), request.stream());
        var call = new GaModelContract.ToolCall("fresh-1", "project_search", Map.of());
        var ex = assertThrows(IllegalArgumentException.class,
                () -> GaResponseContractValidator.validate(noneChoice, responseWith(call)));
        assertEquals("GA native response violates tool choice", ex.getMessage());
    }
}

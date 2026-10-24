package com.specagent.model.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.GaModelContract;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GA Anthropic Messages 协议转换(仅协议形状,不含设置、凭据或重试)。
 *
 * <p>与 {@link GaChatCompletionsAdapter}/{@link GaResponsesAdapter} 相同的宿主控制
 * 无状态全量历史重放语义,但使用 Anthropic 原生工具协议:
 * <ul>
 *   <li>{@code tool_use} 是 assistant content block,其 {@code id} 原样保留为 GA
 *       ToolCall id——绝不生成新 ID;</li>
 *   <li>{@code tool_result} 是紧随其后的 user content block,通过
 *       {@code tool_use_id} 配对;同一批多个工具结果合并进同一条 user 消息的多个
 *       block(官方要求),绝不伪装成普通用户文本;</li>
 *   <li>支持的混合形状为"零或多个 text block 在前,随后一个或多个 tool_use block";
 *       tool_use 之后出现 text、text/tool_use 交错、thinking 与可执行 tool_use 并存
 *       等无法保真续接的形状在解析阶段(释放任何可执行 ToolCall 之前)即拒绝;</li>
 *   <li>{@code thinking}/{@code redacted_thinking} 私有块绝不进入 GA 响应、账本或
 *       Python 线协议;含可执行工具的响应一旦带私有块立即失败关闭(不做完整续接)。</li>
 * </ul>
 */
public final class GaAnthropicMessagesAdapter {

    static final int MAX_TOOL_USE_ID_LENGTH = 128;
    static final int MAX_CONTENT_CHARS = 131072;

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** 构建非流式 Anthropic Messages 请求体(不含 stream 字段)。 */
    public Map<String, Object> requestBody(GaModelContract.Request request, String model) {
        if (model == null || model.isBlank()) throw new IllegalArgumentException("pinned model required");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", request.maxOutputTokens());
        List<String> systemParts = new ArrayList<>();
        List<Object> messages = new ArrayList<>();
        // 连续 tool 消息必须合并为同一条后续 user 消息的多个 tool_result block。
        List<Map<String, Object>> pendingResults = null;
        for (GaModelContract.Message message : request.messages()) {
            switch (message.role()) {
                case "system" -> {
                    flushResults(messages, pendingResults);
                    pendingResults = null;
                    if (!message.content().isBlank()) systemParts.add(message.content());
                }
                case "user" -> {
                    flushResults(messages, pendingResults);
                    pendingResults = null;
                    messages.add(roleMessage("user", message.content()));
                }
                case "assistant" -> {
                    flushResults(messages, pendingResults);
                    pendingResults = null;
                    messages.add(assistantMessage(message));
                }
                case "tool" -> {
                    if (pendingResults == null) pendingResults = new ArrayList<>();
                    pendingResults.add(toolResultBlock(message));
                }
                default -> throw new IllegalArgumentException("Unsupported GA message role");
            }
        }
        flushResults(messages, pendingResults);
        if (!systemParts.isEmpty()) {
            body.put("system", String.join("\n\n", systemParts));
        }
        if (messages.isEmpty()) throw new IllegalArgumentException("Anthropic messages required");
        body.put("messages", messages);
        if (!request.tools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (GaModelContract.Tool tool : request.tools()) {
                // 刻意不发送 strict:true:冻结 Catalog 的 schema 不保证满足 strict 模式
                // 的额外限制;参数合法性由 Java CapabilityRuntime 权威校验。
                Map<String, Object> definition = new LinkedHashMap<>();
                definition.put("name", tool.name());
                definition.put("description", tool.description());
                definition.put("input_schema", tool.parameters());
                tools.add(definition);
            }
            body.put("tools", tools);
            body.put("tool_choice", toolChoice(request.toolChoice()));
        }
        return body;
    }

    /** 解析一个非流式 Anthropic Messages 响应为 GA 通用契约;非法形状立即失败。 */
    public GaModelContract.Response parse(JsonNode root) {
        if (root == null) throw invalid();
        StringBuilder content = new StringBuilder();
        List<GaModelContract.ToolCall> calls = new ArrayList<>();
        try {
            require(root.isObject(), "object required");
            require(isAbsentOrNull(root.get("error")), "provider error");
            JsonNode contentNode = root.get("content");
            require(contentNode != null && contentNode.isArray() && !contentNode.isEmpty(),
                    "content blocks required");
            boolean sawToolUse = false;
            boolean sawThinking = false;
            Set<String> callIds = new HashSet<>();
            for (JsonNode block : contentNode) {
                require(block != null && block.isObject(), "content block required");
                String type = text(block.get("type"), 64);
                switch (type) {
                    case "text" -> {
                        // tool_use 之后的 text 无法用通用 GA 结构保真重放,第一轮拒绝。
                        require(!sawToolUse, "text after tool_use is unsupported");
                        String chunk = text(block.get("text"), MAX_CONTENT_CHARS);
                        require(content.length() + chunk.length() <= MAX_CONTENT_CHARS,
                                "content exceeds bound");
                        content.append(chunk);
                    }
                    case "tool_use" -> {
                        sawToolUse = true;
                        String id = requiredText(block, "id", MAX_TOOL_USE_ID_LENGTH);
                        require(callIds.add(id), "duplicate tool_use id");
                        String name = requiredText(block, "name", 64);
                        require(name.matches("[A-Za-z0-9_-]{1,64}"), "invalid tool name");
                        JsonNode input = block.get("input");
                        require(input != null && input.isObject(), "tool input object required");
                        require(input.size() <= 64, "too many tool input entries");
                        Map<String, Object> arguments = mapper.convertValue(input,
                                new TypeReference<Map<String, Object>>() { });
                        calls.add(new GaModelContract.ToolCall(id, name, arguments));
                    }
                    case "thinking", "redacted_thinking" -> sawThinking = true;
                    default -> throw invalid();
                }
            }
            String stopReason = text(root.get("stop_reason"), 32);
            boolean hasCalls = !calls.isEmpty();
            // thinking 私有块与可执行工具并存时,宿主无法保真续接,执行前失败关闭。
            require(!(sawThinking && hasCalls), "thinking blocks cannot accompany tool_use");
            switch (stopReason) {
                case "tool_use" -> require(hasCalls, "stop_reason tool_use without tool blocks");
                case "end_turn", "stop_sequence" -> {
                    require(!hasCalls, "completed stop_reason with tool blocks");
                    require(content.length() > 0, "empty response");
                }
                // max_tokens 截断、pause_turn、refusal、null 及未知终态都不是成功。
                default -> throw invalid();
            }
            JsonNode usage = root.get("usage");
            require(usage != null && usage.isObject(), "usage required");
            return new GaModelContract.Response(GaModelContract.VERSION,
                    content.toString(), calls, hasCalls ? "tool_calls" : "stop",
                    new GaModelContract.Usage(count(usage, "input_tokens"), count(usage, "output_tokens")));
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalid();
        }
    }

    /**
     * 响应与请求的一致性校验:tool_choice 语义、工具名在冻结 Catalog 内、
     * 以及绝不重放历史已出现过的 tool_use id。必须在释放可执行 ToolCall 之前完成。
     */
    public GaModelContract.Response validateResponse(GaModelContract.Request request,
            GaModelContract.Response response) {
        if (("none".equals(request.toolChoice()) && !response.toolCalls().isEmpty())
                || ("required".equals(request.toolChoice()) && response.toolCalls().isEmpty())) {
            throw new IllegalArgumentException("GA native response violates tool choice");
        }
        Set<String> names = new HashSet<>();
        request.tools().forEach(tool -> names.add(tool.name()));
        Set<String> priorIds = new HashSet<>();
        request.messages().stream()
                .flatMap(message -> message.toolCalls().stream())
                .forEach(call -> priorIds.add(call.id()));
        for (GaModelContract.ToolCall call : response.toolCalls()) {
            if (!names.contains(call.name()) || priorIds.contains(call.id())) {
                throw new IllegalArgumentException("GA native tool identity not authorized");
            }
        }
        return response;
    }

    private static void flushResults(List<Object> messages, List<Map<String, Object>> pendingResults) {
        if (pendingResults == null || pendingResults.isEmpty()) return;
        // 多个 tool_result 组织为同一条后续 user 消息的多个 content block。
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", new ArrayList<>(pendingResults));
        messages.add(user);
    }

    private static Map<String, Object> roleMessage(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private static Map<String, Object> assistantMessage(GaModelContract.Message message) {
        if (message.toolCalls().isEmpty()) {
            return roleMessage("assistant", message.content());
        }
        // 受支持的 assistant 历史形状:text(可选,在前) + tool_use(在后)。
        List<Object> blocks = new ArrayList<>();
        if (message.content() != null && !message.content().isEmpty()) {
            blocks.add(textBlock(message.content()));
        }
        for (GaModelContract.ToolCall call : message.toolCalls()) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "tool_use");
            block.put("id", call.id());
            block.put("name", call.name());
            block.put("input", call.arguments());
            blocks.add(block);
        }
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", blocks);
        return assistant;
    }

    private static Map<String, Object> textBlock(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    private static Map<String, Object> toolResultBlock(GaModelContract.Message message) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", message.toolCallId());
        block.put("content", message.content());
        return block;
    }

    private static Map<String, Object> toolChoice(String choice) {
        // none/auto/required 是 GA 通用合约;Anthropic 对应 none/auto/any。
        // required 映射为 any(强制工具);disable_parallel_tool_use 与 Chat/Responses
        // 传输的 parallel_tool_calls:false 对齐,并行授权仍由 Java Broker 权威判定。
        return switch (choice) {
            case "none" -> Map.of("type", "none");
            case "auto" -> Map.of("type", "auto", "disable_parallel_tool_use", true);
            case "required" -> Map.of("type", "any", "disable_parallel_tool_use", true);
            default -> throw new IllegalArgumentException("invalid tool choice");
        };
    }

    private static boolean isAbsentOrNull(JsonNode node) {
        return node == null || node.isNull();
    }

    private static String requiredText(JsonNode node, String field, int limit) {
        return text(node.get(field), limit);
    }

    private static String text(JsonNode node, int limit) {
        require(node != null && node.isTextual() && !node.textValue().isEmpty()
                && node.textValue().length() <= limit, "invalid text");
        return node.textValue();
    }

    private static int count(JsonNode usage, String field) {
        JsonNode value = usage.get(field);
        require(value != null && value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0,
                "invalid usage");
        return value.intValue();
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid GA Anthropic provider response");
    }
}

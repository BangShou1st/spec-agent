package com.specagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.GaModelContract;
import com.specagent.model.contract.GaModelTarget;
import com.specagent.model.provider.GaNativeStreamException;
import com.specagent.model.provider.KiloGaNativeTransport;
import com.sharedlibs.kilogateway.autoconfigure.KiloGatewayProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:KiloGaNativeQualification.java
 *
 * 用途:Kilo Gateway <b>逐模型</b>的 GA 原生工具调用资格验证(§7)。它走产品自己
 * 的 {@link KiloGaNativeTransport},5 项检查全部通过才允许登记到
 * {@code spec.global-assistant.ga-native.kilo-qualified-models}:
 *
 * <ol>
 *   <li>非流式普通聊天(无工具);</li>
 *   <li>非流式原生工具调用(required);</li>
 *   <li>流式工具调用参数聚合(监听器不得在参数聚合完成前收到可执行内容);</li>
 *   <li>工具结果回填后再次生成(choice=none,必须引用工具结果 token);</li>
 *   <li>无工具的 SUMMARY 请求。</li>
 * </ol>
 *
 * <p>显式运行(不联网失败即如实记录,绝不重试、绝不换模型):
 * {@code backend> gradlew.bat qualifyGaNativeKilo -PkiloModels=a:free,b:free}
 * 或 {@code SPEC_AGENT_GA_KILO_QUALIFICATION_MODELS=...}。
 *
 * <p>证据写入 {@code docs/v2/evidence/KILO_GA_NATIVE_QUALIFICATION.json} 并追加
 * {@code .jsonl};摘要登记进 {@code docs/MODEL_GATEWAY.md §18}。
 */
public final class KiloGaNativeQualification {

    private KiloGaNativeQualification() {
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<String> models = candidateModels();
        Path evidence = Path.of("../docs/v2/evidence/KILO_GA_NATIVE_QUALIFICATION.json");

        KiloGatewayProperties properties = new KiloGatewayProperties();
        properties.setBaseUrl(env("KILO_GATEWAY_BASE_URL", properties.getBaseUrl()));
        properties.setApiKey(env("SPEC_AGENT_GA_KILO_KEY", ""));
        properties.setFreeOnly(false);
        KiloGaNativeTransport transport = new KiloGaNativeTransport(properties);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("recordedAt", Instant.now().toString());
        report.put("provider", "KILO");
        report.put("baseUrl", properties.getBaseUrl());
        report.put("credential", properties.getApiKey().isBlank() ? "anonymous" : "provided");
        report.put("retries", 0);

        var gaRequest = GaModelContract.readRequest(Files.readString(
                Path.of("../contracts/global-assistant/fixtures/ga-model-request-valid.json")));
        List<Map<String, Object>> results = new ArrayList<>();
        int qualified = 0;
        for (String model : models) {
            Map<String, Object> result = qualify(mapper, transport, gaRequest, model);
            results.add(result);
            if ("QUALIFIED".equals(result.get("status"))) qualified++;
            System.out.println(mapper.writeValueAsString(result));
        }
        report.put("models", results);
        report.put("qualifiedCount", qualified);
        report.put("attemptedCount", models.size());

        Files.createDirectories(evidence.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(evidence.toFile(), report);
        Files.writeString(evidence.resolveSibling("KILO_GA_NATIVE_QUALIFICATION.jsonl"),
                mapper.writeValueAsString(report) + "\n", java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        System.out.println("[kilo-ga-qualification] qualified=" + qualified + "/" + models.size()
                + " evidence=" + evidence.toAbsolutePath());
    }

    private static Map<String, Object> qualify(ObjectMapper mapper, KiloGaNativeTransport transport,
                                               GaModelContract.Request template, String model) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", model);
        int calls = 0;
        try {
            GaModelTarget target = new GaModelTarget("KILO", model, "kilo-ga-qualification", null);
            List<GaModelContract.Tool> tools = template.tools();

            // 1) 非流式普通聊天(无工具)
            calls++;
            var chat = transport.complete(target, request(template, List.of(
                    msg("user", "Reply with the single word: ok")), List.of(), "none"), Duration.ofSeconds(90), () -> true);
            if (chat.content().isBlank() || !"stop".equals(chat.finishReason()))
                throw new IllegalArgumentException("plain chat did not return text");
            result.put("plainChat", "PASS");

            // 2) 非流式原生工具调用(required)
            calls++;
            var toolRequest = request(template, List.of(
                    msg("system", "Call project_search exactly once with query GA_NATIVE_QUALIFICATION. Then report the tool result token verbatim. Do not invent a result."),
                    msg("user", "Search the qualification project.")), tools, "required");
            var first = transport.complete(target, toolRequest, Duration.ofSeconds(90), () -> true);
            if (first.toolCalls().size() != 1 || !"project_search".equals(first.toolCalls().getFirst().name())
                    || !Map.of("query", "GA_NATIVE_QUALIFICATION").equals(first.toolCalls().getFirst().arguments()))
                throw new IllegalArgumentException("unexpected tool call");
            result.put("nativeToolCall", "PASS");

            // 3) 流式工具调用参数聚合(监听器不得在参数聚合完成前收到可执行内容)
            calls++;
            List<String> fragments = new ArrayList<>();
            var streamed = transport.stream(target, toolRequest, Duration.ofSeconds(90), () -> true,
                    text -> {
                        fragments.add(text);
                        return true;
                    });
            if (streamed.toolCalls().size() != 1
                    || !"project_search".equals(streamed.toolCalls().getFirst().name())
                    || !Map.of("query", "GA_NATIVE_QUALIFICATION").equals(streamed.toolCalls().getFirst().arguments()))
                throw new IllegalArgumentException("streamed tool call aggregation mismatch");
            result.put("streamToolCall", "PASS");
            result.put("streamCandidateTextFragments", fragments.size());

            // 4) 工具结果回填后续答(choice=none,必须引用工具结果 token)
            String token = "QUALIFICATION_RESULT_" + UUID.randomUUID();
            List<GaModelContract.Message> followupMessages = new ArrayList<>(toolRequest.messages());
            followupMessages.add(new GaModelContract.Message("assistant", first.content(),
                    first.toolCalls(), null));
            followupMessages.add(new GaModelContract.Message("tool",
                    mapper.writeValueAsString(Map.of("result", token)), null,
                    first.toolCalls().getFirst().id()));
            calls++;
            var followup = transport.complete(target,
                    request(template, followupMessages, tools, "none"), Duration.ofSeconds(90), () -> true);
            if (!followup.toolCalls().isEmpty() || !followup.content().contains(token))
                throw new IllegalArgumentException("tool result followup invalid");
            result.put("toolResultFollowup", "PASS");

            // 5) 无工具的 SUMMARY 请求
            calls++;
            var summary = transport.complete(target, request(template, List.of(
                    msg("user", "Summarise: the qualification ran and produced no user-visible side effects.")),
                    List.of(), "none"), Duration.ofSeconds(90), () -> true);
            if (summary.content().isBlank()) throw new IllegalArgumentException("summary returned no text");
            result.put("summary", "PASS");

            result.put("status", "QUALIFIED");
        } catch (GaNativeStreamException streamFailure) {
            result.put("status", "FAIL");
            result.put("failureCode", streamFailure.getMessage());
            result.put("frameShapes", streamFailure.frames());
        } catch (Exception failure) {
            result.put("status", "FAIL");
            result.put("failureType", failure.getClass().getSimpleName());
            // 绝不记录凭据、正文或提供商原始报文。
        }
        result.put("providerCalls", calls);
        return result;
    }

    private static GaModelContract.Message msg(String role, String content) {
        return new GaModelContract.Message(role, content, null, null);
    }

    private static GaModelContract.Request request(GaModelContract.Request template,
            List<GaModelContract.Message> messages, List<GaModelContract.Tool> tools, String choice) {
        return new GaModelContract.Request(GaModelContract.VERSION, UUID.randomUUID(), 1, UUID.randomUUID(),
                UUID.randomUUID(), "AGENT", UUID.randomUUID(), messages, tools, choice, 1024, false);
    }

    /** {@code -PkiloModels=a,b} (system property) 优先于环境变量,最后回落到督查过的候选集。 */
    private static List<String> candidateModels() {
        String raw = System.getProperty("kilo.qualification.models");
        if (raw == null || raw.isBlank()) raw = System.getenv("SPEC_AGENT_GA_KILO_QUALIFICATION_MODELS");
        if (raw == null || raw.isBlank()) raw = String.join(",", List.of(
                "nvidia/nemotron-3-super-120b-a12b:free",
                "nvidia/nemotron-3-ultra-550b-a55b:free",
                "thinkingmachines/inkling-small:free",
                "xingchen-agi/xing4.0-29b-a4b:free",
                "dots-studio/dots-3-note-preview:free",
                "cohere/north-mini-code:free",
                "nvidia/nemotron-3.5-lightning:free",
                "poolside/laguna-s-2.1:free"));
        List<String> models = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) models.add(part.trim());
        }
        return List.copyOf(models);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

package com.specagent.model.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.GaModelContract;
import com.specagent.model.contract.StreamCancelledException;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Bounded native SSE aggregation. No partial argument is ever executable. */
public final class GaNativeSseDecoder {
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public GaModelContract.Response decode(InputStream input, BooleanSupplier active) {
        return decode(input, active, text -> true);
    }

    /** Candidate text only; arguments and reasoning never enter the text channel. */
    public GaModelContract.Response decode(InputStream input, BooleanSupplier active,
            com.specagent.model.contract.FragmentListener listener) {
        State state = new State(listener);
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var reader = new BufferedReader(new InputStreamReader(new LimitedInput(input), decoder))) {
            StringBuilder event = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                checkActive(active);
                if (line.isEmpty()) {
                    if (!event.isEmpty()) {
                        String data = event.toString();
                        event.setLength(0);
                        if (data.equals("[DONE]")) {
                            checkActive(active);
                            return state.finish();
                        }
                        state.accept(mapper.readTree(data));
                    }
                } else if (line.startsWith("data:")) {
                    if (!event.isEmpty()) event.append('\n');
                    event.append(line.substring(5).stripLeading());
                    if (event.length() > 262144) throw new IllegalArgumentException();
                } else if (!line.startsWith(":")) {
                    // SSE metadata is allowed, but unknown fields are not treated as data.
                    if (!line.startsWith("event:") && !line.startsWith("id:") && !line.startsWith("retry:"))
                        throw new IllegalArgumentException();
                }
            }
            throw new IllegalArgumentException(); // EOF never upgrades a partial tool call.
        } catch (StreamCancelledException ex) {
            throw ex;
        } catch (Exception ex) {
            checkActive(active);
            throw new GaNativeStreamException(state.stage, state.frames);
        }
    }

    private static void checkActive(BooleanSupplier active) {
        if (!active.getAsBoolean()) throw new StreamCancelledException("GA execution no longer active");
    }

    private final class State {
        final com.specagent.model.contract.FragmentListener listener;
        State(com.specagent.model.contract.FragmentListener listener) { this.listener = listener; }
        final StringBuilder content = new StringBuilder();
        final java.util.SortedMap<Integer,CallState> toolCalls=new java.util.TreeMap<>();
        String finishReason;
        JsonNode usage;
        boolean call;
        String stage = "EVENT";
        final java.util.ArrayList<Map<String, Object>> frames = new java.util.ArrayList<>();

        void accept(JsonNode root) {
            if (root != null && frames.size() < 32) {
                var choice = root.path("choices").path(0);
                String reason = choice.path("finish_reason").asText("");
                frames.add(Map.of("choices", root.path("choices").isArray() ? root.path("choices").size() : -1,
                        "finish", java.util.Set.of("stop", "tool_calls", "length", "").contains(reason) ? reason : "OTHER",
                        "delta", choice.path("delta").isObject(), "deltaEmpty", choice.path("delta").isEmpty(),
                        "contentChars", choice.path("delta").path("content").isTextual() ? choice.path("delta").path("content").textValue().length() : 0,
                        "toolFragments", choice.path("delta").path("tool_calls").isArray() ? choice.path("delta").path("tool_calls").size() : 0,
                        "usage", root.hasNonNull("usage"), "toolIndexes", indexes(choice.path("delta").path("tool_calls"))));
            }
            stage = "CHOICES";
            require(root != null && root.isObject() && !root.has("error"));
            JsonNode choices = root.get("choices");
            require(choices != null && choices.isArray() && choices.size() <= 1);
            JsonNode nextUsage = root.get("usage");
            stage = "USAGE";
            if (nextUsage != null && !nextUsage.isNull()) {
                require(usage == null && nextUsage.isObject());
                usage = nextUsage;
            }
            if (choices.isEmpty()) { require(nextUsage != null && !nextUsage.isNull()); return; }
            JsonNode choice = choices.get(0);
            stage = "CHOICE_INDEX_TYPE";
            // Zen may omit index for its sole choice. A present index must still be exactly integer zero.
            require(!choice.has("index") || (choice.path("index").isIntegralNumber() && choice.path("index").canConvertToInt()));
            stage = "CHOICE_INDEX_VALUE";
            require(!choice.has("index") || choice.path("index").intValue() == 0);
            stage = "POST_FINISH";
            if (finishReason != null) {
                // Zen sends a final usage frame with a single empty choice instead of choices=[].
                JsonNode tail = choice.get("delta"), reason = choice.get("finish_reason");
                require(nextUsage != null && !nextUsage.isNull() && emptyTail(tail)
                        && (reason == null || reason.isNull() || (reason.isTextual() && reason.textValue().equals(finishReason))));
                return;
            }
            stage = "DELTA";
            JsonNode delta = choice.get("delta");
            require(delta != null && delta.isObject());
            if (delta.has("role")) require("assistant".equals(delta.path("role").asText()));
            stage = "CONTENT";
            append(content, delta.get("content"));
            JsonNode calls = delta.get("tool_calls");
            if (calls != null && !calls.isNull()) {
                stage = "CALL_INDEX";
                require(calls.isArray() && calls.size() <= 5);
                var frameIndexes=new java.util.HashSet<Integer>();
                for (JsonNode fragment : calls) {
                    JsonNode index=fragment.path("index");
                    require(index.isIntegralNumber() && index.canConvertToInt() && index.intValue()>=0 && index.intValue()<5 && frameIndexes.add(index.intValue()));
                    var target=toolCalls.computeIfAbsent(index.intValue(),ignored->new CallState());
                    call = true;
                    stage = "CALL_IDENTITY";
                    JsonNode identity=fragment.get("id");
                    if(identity!=null && !identity.isNull()) {
                        require(identity.isTextual() && !identity.textValue().isBlank());
                        if(target.id.isEmpty()) append(target.id,identity);
                        else require(target.id.toString().equals(identity.textValue()));
                    }
                    if (fragment.has("type")) {
                        require("function".equals(fragment.path("type").asText()));
                        target.functionType = true;
                    }
                    JsonNode function = fragment.get("function");
                    require(function != null && function.isObject());
                    append(target.name, function.get("name"));
                    append(target.arguments, function.get("arguments"));
                }
            }
            JsonNode reason = choice.get("finish_reason");
            stage = "FINISH_REASON";
            if (reason != null && !reason.isNull()) {
                require(reason.isTextual());
                if (!reason.textValue().isEmpty()) finishReason = reason.textValue();
            }
            stage = "TERMINATION";
            JsonNode text = delta.get("content");
            // Validate the entire frame before releasing text from it. Tool-bearing frames
            // are not answer drafts; earlier candidates are retracted by the Agent adapter.
            if (!call && text != null && text.isTextual() && !text.textValue().isEmpty()
                    && !listener.onFragment(text.textValue()))
                throw new StreamCancelledException("GA text consumer closed");
        }

        GaModelContract.Response finish() throws IOException {
            stage = "FINISH_AND_USAGE";
            require(finishReason != null && usage != null);
            Map<String, Object> message = new java.util.LinkedHashMap<>();
            message.put("role", "assistant");
            message.put("content", content.toString());
            if (call) {
                var values=new java.util.ArrayList<Map<String,Object>>(); int expected=0;
                for(var entry:toolCalls.entrySet()) {
                    var target=entry.getValue(); require(entry.getKey()==expected++ && target.functionType);
                    values.add(Map.of("id",target.id.toString(),"type","function","function",Map.of("name",target.name.toString(),"arguments",target.arguments.toString())));
                }
                message.put("tool_calls",values);
            }
            // Reuse strict native validation: object arguments, duplicate keys, finish/call match and usage.
            stage = "COMPLETED_RESPONSE";
            return new GaChatCompletionsAdapter().parse(mapper.writeValueAsString(Map.of(
                    "choices", List.of(Map.of("message", message, "finish_reason", finishReason)), "usage", usage)));
        }
    }

    private static final class CallState {
        final StringBuilder id=new StringBuilder(),name=new StringBuilder(),arguments=new StringBuilder();
        boolean functionType;
    }
    private static java.util.List<String> indexes(JsonNode calls) {
        if(!calls.isArray()) return java.util.List.of();
        var out=new java.util.ArrayList<String>();
        for(var call:calls) { var index=call.get("index"); out.add(index==null?"ABSENT":index.isIntegralNumber() && index.canConvertToInt()?Integer.toString(index.intValue()):"INVALID"); if(out.size()>=6) break; }
        return java.util.List.copyOf(out);
    }
    private static void append(StringBuilder target, JsonNode value) {
        if (value == null || value.isNull()) return;
        require(value.isTextual());
        target.append(value.textValue());
        require(target.length() <= 131072);
    }

    private static boolean emptyTail(JsonNode delta) {
        if (delta == null || !delta.isObject()) return false;
        for (var entry : delta.properties()) {
            JsonNode value = entry.getValue();
            switch (entry.getKey()) {
                case "role" -> { if (!value.isNull() && !"assistant".equals(value.asText())) return false; }
                case "content", "reasoning_content" -> {
                    if (!value.isNull() && (!value.isTextual() || !value.textValue().isEmpty())) return false;
                }
                case "tool_calls" -> { if (!value.isNull() && (!value.isArray() || !value.isEmpty())) return false; }
                default -> { return false; }
            }
        }
        return true;
    }

    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException(); }

    private static final class LimitedInput extends FilterInputStream {
        private int remaining = 1048576;
        LimitedInput(InputStream input) { super(input); }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && --remaining < 0) throw new IOException("GA stream limit exceeded");
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, Math.min(length, Math.max(1, remaining + 1)));
            if (count > 0 && (remaining -= count) < 0) throw new IOException("GA stream limit exceeded");
            return count;
        }
    }
}

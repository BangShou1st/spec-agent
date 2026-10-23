package com.specagent.assistant.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.assistant.conversation.GaRunActorRepository;
import com.specagent.modelsettings.ModelCredentialCrypto;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Encrypted sidecar for provider-private continuation material.
 *
 * <p>The row is always created before the MODEL ledger is completed and inside
 * the same broker transaction; a random UUID is used as the opaque reference.
 * Reads never treat the string as authorization: run ownership, thread, model
 * binding, provider identity and tool-call identities are checked separately.
 */
@Service
public class GaModelContinuationStore {
    private static final int MAX_STATE_BYTES = 4096;
    private final NamedParameterJdbcTemplate jdbc;
    private final ModelCredentialCrypto crypto;
    private final GaRunActorRepository actors;
    private final ObjectMapper mapper = new ObjectMapper();

    public GaModelContinuationStore(NamedParameterJdbcTemplate jdbc, ModelCredentialCrypto crypto,
            GaRunActorRepository actors) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.actors = actors;
    }

    public record Source(UUID orgId, UUID threadId, UUID sourceRunId, UUID sourceCallId) {}

    /**
     * Extracts a per-tool-call Gemini continuation map. Every call that carries
     * private material must use exactly one `google.thought_signature` entry;
     * unrelated namespaces and mixed shapes fail closed instead of being merged.
     */
    public Map<String, Object> extract(List<String> toolCallIds, List<Map<String, Object>> perCallExtra) {
        require(toolCallIds != null && perCallExtra != null && toolCallIds.size() == perCallExtra.size(),
                "continuation tool identity mismatch");
        Map<String, Object> byToolCall = new LinkedHashMap<>();
        boolean any = false;
        for (int index = 0; index < toolCallIds.size(); index++) {
            Map<String, Object> extra = perCallExtra.get(index);
            if (extra == null || extra.isEmpty()) continue;
            require(extra.size() == 1 && "google".equals(extra.keySet().iterator().next())
                            && extra.get("google") instanceof Map<?, ?> nested && nested.size() == 1
                            && nested.get("thought_signature") instanceof String signature
                            && !signature.isBlank() && signature.length() <= 1024,
                    "unsupported continuation material");
            byToolCall.put(toolCallIds.get(index), extra);
            any = true;
        }
        // Mixed signed/unsigned batches cannot be restored completely on the next
        // turn; fail now, before Python can execute any tool from this response.
        int unsigned = (int) perCallExtra.stream().filter(extra -> extra == null || extra.isEmpty()).count();
        if (any && unsigned > 0) {
            throw new IllegalArgumentException("unsupported mixed continuation material");
        }
        return any ? byToolCall : null;
    }

    public String persist(Source source, GaExecutionStore.Binding binding, UUID sourceCallId,
            List<String> toolCallIds, Map<String, Object> state) {
        return persist(source, binding, sourceCallId, toolCallIds, state, UUID.randomUUID());
    }

    public String persist(Source source, GaExecutionStore.Binding binding, UUID sourceCallId,
            List<String> toolCallIds, Map<String, Object> state, UUID assignedId) {
        require(source != null && source.orgId() != null && source.threadId() != null && source.sourceRunId() != null,
                "continuation ownership required");
        require(binding != null && toolCallIds != null && !toolCallIds.isEmpty() && toolCallIds.size() <= 5,
                "continuation tool identity required");
        String canonical;
        String encrypted;
        try {
            canonical = mapper.writeValueAsString(state);
            require(canonical.getBytes(StandardCharsets.UTF_8).length <= MAX_STATE_BYTES,
                    "continuation state exceeds limit");
            encrypted = crypto.encryptScoped(canonical, source.orgId(),
                    "ga_model_continuations", source.sourceRunId() + ":" + sourceCallId);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid GA model continuation state");
        }
        UUID id = assignedId;
        var params = new LinkedHashMap<String, Object>();
        params.put("id", id);
        params.put("org", source.orgId());
        params.put("thread", source.threadId());
        params.put("run", source.sourceRunId());
        params.put("call", sourceCallId);
        params.put("provider", binding.provider());
        params.put("model", binding.model());
        params.put("revision", binding.settingsRevision());
        params.put("toolIds", toolCallIdsJson(toolCallIds));
        params.put("count", toolCallIds.size());
        params.put("length", canonical.getBytes(StandardCharsets.UTF_8).length);
        params.put("payload", encrypted);
        jdbc.update("""
                INSERT INTO ga_model_continuations(id,org_id,thread_id,source_run_id,source_call_id,provider,
                  model,settings_revision,tool_call_ids,payload_count,payload_length,payload)
                VALUES(:id,:org,:thread,:run,:call,:provider,:model,:revision,
                  :toolIds,:count,:length,:payload)
                """, params);
        return "gacr-" + id;
    }

    public record Resolved(UUID orgId, UUID threadId, UUID sourceRunId, UUID sourceCallId,
                           String provider, String model, String settingsRevision,
                           List<String> toolCallIds, Map<String, Object> state) {}

    /**
     * Resolves a reference for an active binding. The ref itself is not an
     * authorization token; callers must already hold a live run scope.
     */
    @Transactional
    public Resolved resolve(String ref, GaExecutionStore.Scope scope, GaExecutionStore.Binding binding) {
        require(ref != null && ref.matches("gacr-[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"),
                "invalid continuation reference");
        UUID id;
        try { id = UUID.fromString(ref.substring("gacr-".length())); }
        catch (Exception ex) { throw new IllegalArgumentException("invalid continuation reference"); }
        var actor = actors.require(scope.runId());
        var rows = jdbc.queryForList("""
                SELECT c.*, e.thread_id AS active_thread,
                       e.provider AS active_provider, e.model AS active_model,
                       e.settings_revision AS active_revision
                FROM ga_model_continuations c
                JOIN ga_executions e ON e.run_id=:run AND e.execution_epoch=:epoch AND e.lease_id=:lease
                JOIN global_assistant_runs r ON r.id=e.run_id AND r.status='RUNNING'
                    AND r.cancel_requested_at IS NULL
                WHERE c.id=:id AND c.org_id=:org
                """, new LinkedHashMap<>(Map.of("id", id, "org", actor.orgId(), "run", scope.runId(),
                "epoch", scope.epoch(), "lease", scope.leaseId())));
        if (rows.size() != 1) throw new IllegalStateException("GA_CONTINUATION_NOT_AUTHORIZED");
        var row = rows.getFirst();
        require(row.get("thread_id").equals(row.get("active_thread"))
                && binding.threadId().equals(row.get("thread_id"))
                && row.get("provider").equals(row.get("active_provider"))
                && row.get("model").equals(row.get("active_model"))
                && row.get("settings_revision").equals(row.get("active_revision")),
                "GA_CONTINUATION_BINDING_MISMATCH");
        String success = jdbc.queryForObject("""
                SELECT status FROM ga_execution_calls WHERE run_id=:run AND call_id=:call AND kind='MODEL'
                """, Map.of("run", row.get("source_run_id"), "call", row.get("source_call_id")), String.class);
        require("SUCCEEDED".equals(success), "GA_CONTINUATION_SOURCE_NOT_SUCCESSFUL");
        String plain = crypto.decryptScoped((String) row.get("payload"), actor.orgId(),
                "ga_model_continuations", row.get("source_run_id") + ":" + row.get("source_call_id"));
        Map<String, Object> state;
        try {
            state = mapper.readValue(plain, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            List<Map<String, Object>> values = new ArrayList<>();
            for (Object value : state.values()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) value;
                values.add(typed);
            }
            require(mapper.writeValueAsString(state).equals(mapper.writeValueAsString(
                            extract(new ArrayList<>(state.keySet()), values))),
                    "unsupported continuation material");
        } catch (Exception ex) {
            throw new IllegalStateException("GA_CONTINUATION_STATE_INVALID");
        }
        List<String> toolIds;
        try {
            toolIds = mapper.readValue((String) row.get("tool_call_ids"),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalStateException("GA_CONTINUATION_STATE_INVALID");
        }
        return new Resolved(actor.orgId(), (UUID) row.get("thread_id"), (UUID) row.get("source_run_id"),
                (UUID) row.get("source_call_id"), (String) row.get("provider"), (String) row.get("model"),
                (String) row.get("settings_revision"), List.copyOf(toolIds), state);
    }

    /** Parser-compatible helper for storing a compact JSON array of tool call IDs. */
    public static String toolCallIdsJson(List<String> ids) {
        return ids.stream().reduce(new StringBuilder("["), (builder, id) -> {
            if (builder.length() > 1) builder.append(',');
            return builder.append('"').append(id).append('"');
        }, StringBuilder::append).append(']').toString();
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}

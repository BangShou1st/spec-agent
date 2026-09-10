package com.specagent.globalassistant.turn;

import com.fasterxml.jackson.core.type.TypeReference;
import com.specagent.common.Json;
import com.specagent.common.Maps;
import com.specagent.globalassistant.context.GlobalAssistantContextBuilder;
import com.specagent.globalassistant.conversation.GlobalAssistantConversationService;
import com.specagent.globalassistant.conversation.GlobalAssistantMessage;
import com.specagent.globalassistant.conversation.GlobalAssistantRun;
import com.specagent.globalassistant.conversation.GlobalAssistantRunRepository;
import com.specagent.globalassistant.model.GlobalAssistantPromptRenderer;
import com.specagent.globalassistant.tool.GlobalAssistantToolCatalog;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backend-owned steer handoff. Owns accept/claim/successor/recover/discard.
 * Runtime stays responsible for one-run execution only.
 */
@Service
public class TurnHandoffService {
    private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {
    };
    private final NamedParameterJdbcTemplate jdbc;
    private final PendingTurnRepository pending;
    private final GlobalAssistantRunRepository runs;
    private final GlobalAssistantConversationService conversations;
    private final Json json;

    public TurnHandoffService(NamedParameterJdbcTemplate jdbc, PendingTurnRepository pending,
            GlobalAssistantRunRepository runs, GlobalAssistantConversationService conversations, Json json) {
        this.jdbc = jdbc;
        this.pending = pending;
        this.runs = runs;
        this.conversations = conversations;
        this.json = json;
    }

    public record AcceptResult(PendingTurn pendingTurn, Optional<Successor> successor) {
    }

    public record Successor(UUID pendingId, GlobalAssistantRun run, String message, GlobalAssistantContextBuilder.UiRequest uiRequest) {
    }

    private void lockThread(UUID threadId) {
        var rows = jdbc.queryForList("SELECT id FROM global_assistant_threads WHERE id = :id FOR UPDATE", Maps.of("id", threadId), UUID.class);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Global assistant thread not found: " + threadId);
        }
    }

    @Transactional
    public AcceptResult acceptSteer(UUID threadId, UUID targetRunId, String rawMessage, GlobalAssistantContextBuilder.UiRequest uiRequest) {
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new SteerRejectedException(SteerRejectedException.Reason.BLANK, "Steer message must not be blank");
        }
        String message = rawMessage.trim();
        if (message.length() > 4000) {
            throw new SteerRejectedException(SteerRejectedException.Reason.TOO_LONG, "Steer message too long");
        }
        lockThread(threadId);
        com.specagent.globalassistant.conversation.GlobalAssistantRun freshTarget;
        try {
            freshTarget = runs.lockById(targetRunId);
        } catch (IllegalArgumentException ex) {
            throw new SteerRejectedException(SteerRejectedException.Reason.RUN_NOT_FOUND, "Run not found");
        }
        if (!freshTarget.threadId().equals(threadId)) {
            throw new SteerRejectedException(SteerRejectedException.Reason.THREAD_MISMATCH, "Run does not belong to thread");
        }
        var currentActive = runs.findActiveByThread(threadId);
        if (!freshTarget.status().isActive()) {
            if (currentActive.isPresent() && !currentActive.get().id().equals(targetRunId)) {
                throw new SteerRejectedException(SteerRejectedException.Reason.STALE_TARGET, "Target run is stale; thread already hosts a newer active run");
            }
        }
        String uiJson = uiContextJson(uiRequest);
        PendingTurn created = pending.insert(threadId, targetRunId, message, uiJson);
        var lockedTarget = runs.lockById(targetRunId);
        if (lockedTarget.status().isActive()) {
            runs.requestCancel(targetRunId);
            return new AcceptResult(created, Optional.empty());
        }
        var resealedActive = runs.findActiveByThread(threadId);
        if (resealedActive.isPresent()) {
            throw new SteerRejectedException(SteerRejectedException.Reason.STALE_TARGET, "Target terminalized but a newer active run exists");
        }
        Optional<Successor> successor = claimAndCreateSuccessorLocked(threadId);
        var claimed = successor.map(s -> pending.findById(s.pendingId()).orElseThrow()).orElse(created);
        return new AcceptResult(claimed, successor);
    }

    @Transactional
    public Optional<Successor> tryHandoff(UUID threadId) {
        return doHandoff(threadId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Successor> tryHandoffAfterCommit(UUID threadId) {
        return doHandoff(threadId);
    }

    private Optional<Successor> doHandoff(UUID threadId) {
        lockThread(threadId);
        if (runs.findActiveByThread(threadId).isPresent()) {
            return Optional.empty();
        }
        return claimAndCreateSuccessorLocked(threadId);
    }

    @Transactional
    public com.specagent.globalassistant.turn.ThreadActivity stopThreadAtomically(UUID threadId) {
        lockThread(threadId);
        pending.markDiscardedByThread(threadId);
        var active = runs.findActiveByThread(threadId);
        active.ifPresent(run -> runs.requestCancel(run.id()));
        return new com.specagent.globalassistant.turn.ThreadActivity(runs.findActiveByThread(threadId), pending.findUnresolvedByThread(threadId));
    }

    private Optional<Successor> claimAndCreateSuccessorLocked(UUID threadId) {
        Optional<PendingTurn> unresolved;
        try {
            unresolved = Optional.of(pending.lockUnresolvedByThread(threadId));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        PendingTurn pt = unresolved.get();
        if (runs.findActiveByThread(threadId).isPresent()) {
            return Optional.empty();
        }
        if ("CLAIMED".equals(pt.status().name())) {
            if (pt.successorRunId() != null) {
                var existing = runs.findById(pt.successorRunId());
                if (existing.isPresent()) {
                    pending.markConsumed(pt.id(), pt.successorRunId());
                    return Optional.of(new Successor(pt.id(), existing.get(), pt.message(), parseUiRequest(pt.uiContextJson())));
                }
            }
        } else {
            pending.markClaimed(pt.id());
        }
        GlobalAssistantRun successor;
        try {
            successor = conversations.createRunWithUserMessage(threadId, pt.message(),
                    GlobalAssistantPromptRenderer.PROMPT_VERSION,
                    GlobalAssistantContextBuilder.CONTEXT_PROJECTION_VERSION,
                    GlobalAssistantToolCatalog.FINGERPRINT);
        } catch (com.specagent.globalassistant.conversation.GlobalAssistantRunActiveException ex) {
            return Optional.empty();
        }
        pending.markConsumed(pt.id(), successor.id());
        return Optional.of(new Successor(pt.id(), successor, pt.message(), parseUiRequest(pt.uiContextJson())));
    }

    @Transactional
    public int discardUnresolved(UUID threadId) {
        lockThread(threadId);
        return pending.markDiscardedByThread(threadId);
    }

    @Transactional
    public int recoverStranded() {
        var stranded = pending.findStrandedPending();
        int created = 0;
        for (PendingTurn pt : stranded) {
            try {
                var tx = tryHandoff(pt.threadId());
                if (tx.isPresent()) {
                    created++;
                }
            } catch (Exception ignored) {
            }
        }
        return created;
    }

    public java.util.List<GlobalAssistantMessage> listMessages(UUID threadId) {
        return conversations.listMessages(threadId);
    }

    private String uiContextJson(GlobalAssistantContextBuilder.UiRequest uiRequest) {
        if (uiRequest == null) {
            return "{}";
        }
        try {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("currentPage", uiRequest.currentPage());
            if (uiRequest.selectedEntity() != null) {
                map.put("selectedEntity", Map.of("type", String.valueOf(uiRequest.selectedEntity().type()), "id", String.valueOf(uiRequest.selectedEntity().id())));
            }
            return json.write(map);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private GlobalAssistantContextBuilder.UiRequest parseUiRequest(String uiJson) {
        if (uiJson == null || uiJson.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = json.read(uiJson, MAP_REF);
            if (map == null) {
                return null;
            }
            Object page = map.get("currentPage");
            Object sel = map.get("selectedEntity");
            GlobalAssistantContextBuilder.UiRequest.SelectedRef selected = null;
            if (sel instanceof Map<?, ?> m) {
                Object t = m.get("type");
                Object i = m.get("id");
                if (t != null && i != null) {
                    selected = new GlobalAssistantContextBuilder.UiRequest.SelectedRef(String.valueOf(t), String.valueOf(i));
                }
            }
            return new GlobalAssistantContextBuilder.UiRequest(page == null ? null : String.valueOf(page), selected);
        } catch (Exception ex) {
            return null;
        }
    }
}

package com.specagent.globalassistant.turn;

import com.specagent.common.Maps;
import com.specagent.globalassistant.conversation.GlobalAssistantRunRepository;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conversation delete owner. Removes GA conversation rows only;
 * durable product-side effects (projects) are never rolled back.
 */
@Service
public class ConversationDeleteService {
    private final NamedParameterJdbcTemplate jdbc;
    private final GlobalAssistantRunRepository runs;
    private final PendingTurnRepository pending;

    public ConversationDeleteService(NamedParameterJdbcTemplate jdbc, GlobalAssistantRunRepository runs, PendingTurnRepository pending) {
        this.jdbc = jdbc;
        this.runs = runs;
        this.pending = pending;
    }

    @Transactional
    public void deleteThread(UUID threadId) {
        var active = runs.findActiveByThread(threadId);
        if (active.isPresent()) {
            throw new IllegalStateException("THREAD_ACTIVE");
        }
        if (pending.findUnresolvedByThread(threadId).isPresent()) {
            throw new IllegalStateException("THREAD_ACTIVE");
        }
        var runIds = jdbc.queryForList("SELECT id FROM global_assistant_runs WHERE thread_id = :threadId", Maps.of("threadId", threadId), UUID.class);
        if (!runIds.isEmpty()) {
            jdbc.update("DELETE FROM global_assistant_run_events WHERE run_id IN (:runIds)", Maps.of("runIds", runIds));
            jdbc.update("DELETE FROM capability_invocations WHERE run_id IN (:runIds)", Maps.of("runIds", runIds));
        }
        pending.deleteByThread(threadId);
        jdbc.update("DELETE FROM global_assistant_messages WHERE thread_id = :threadId", Maps.of("threadId", threadId));
        jdbc.update("DELETE FROM global_assistant_runs WHERE thread_id = :threadId", Maps.of("threadId", threadId));
        int deleted = jdbc.update("DELETE FROM global_assistant_threads WHERE id = :threadId", Maps.of("threadId", threadId));
        if (deleted != 1) {
            throw new IllegalArgumentException("Thread not found: " + threadId);
        }
    }
}

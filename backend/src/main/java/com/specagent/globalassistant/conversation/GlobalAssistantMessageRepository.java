package com.specagent.globalassistant.conversation;

import com.specagent.common.Ids;
import com.specagent.common.Maps;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Append + deterministic ordered read of user-facing messages. The canonical
 * conversation order is the append-monotonic per-thread sequence; created_at
 * stays as time metadata only.
 */
@Repository
public class GlobalAssistantMessageRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<GlobalAssistantMessage> rowMapper;
    public GlobalAssistantMessageRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.rowMapper = (rs, rowNum) -> new GlobalAssistantMessage(
                rs.getObject("id", UUID.class),
                rs.getObject("thread_id", UUID.class),
                GlobalAssistantMessage.Role.fromCode(rs.getString("role")),
                rs.getString("content"),
                rs.getObject("run_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getLong("sequence"));
    }
    /**
     * Appends one message with the next per-thread sequence. The thread row
     * lock serializes concurrent appenders of one thread, so a later append
     * always observes a greater sequence; a rolled-back transaction leaves
     * no row behind. Gaps from rolled-back allocations are harmless: only
     * the order is contractual.
     */
    @org.springframework.transaction.annotation.Transactional
    public GlobalAssistantMessage append(UUID threadId, GlobalAssistantMessage.Role role, String content, UUID runId) {
        List<UUID> locked = jdbc.queryForList(
                "SELECT id FROM global_assistant_threads WHERE id = :threadId FOR UPDATE",
                Maps.of("threadId", threadId), UUID.class);
        if (locked.isEmpty()) {
            throw new IllegalArgumentException("Global assistant thread not found: " + threadId);
        }
        Long max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sequence), 0) FROM global_assistant_messages WHERE thread_id = :threadId",
                Maps.of("threadId", threadId), Long.class);
        long next = (max == null ? 0 : max) + 1;
        UUID id = Ids.random();
        Instant now = Instant.now();
        jdbc.update(
                "INSERT INTO global_assistant_messages (id, thread_id, role, content, run_id, created_at, sequence) VALUES (:id, :threadId, :role, :content, :runId, :now, :sequence)",
                Maps.of("id", id, "threadId", threadId, "role", role.name(), "content", content, "runId", runId,
                        "now", Timestamp.from(now), "sequence", next));
        return new GlobalAssistantMessage(id, threadId, role, content, runId, now, next);
    }
    /**
     * Canonical conversation order: append sequence only.
     */
    public List<GlobalAssistantMessage> findByThread(UUID threadId) {
        return jdbc.query(
                "SELECT * FROM global_assistant_messages WHERE thread_id = :threadId ORDER BY sequence",
                Maps.of("threadId", threadId), rowMapper);
    }
    public List<GlobalAssistantMessage> findRecentByThread(UUID threadId, int limit) {
        List<GlobalAssistantMessage> all = findByThread(threadId);
        if (all.size() <= limit) {
            return all;
        }
        return all.subList(all.size() - limit, all.size());
    }
}

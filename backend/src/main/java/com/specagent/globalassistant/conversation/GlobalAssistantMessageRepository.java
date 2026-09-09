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
 * Append + deterministic ordered read of user-facing messages.
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
                rs.getTimestamp("created_at").toInstant());
    }
    public GlobalAssistantMessage append(UUID threadId, GlobalAssistantMessage.Role role, String content, UUID runId) {
        UUID id = Ids.random();
        Instant now = Instant.now();
        jdbc.update(
                "INSERT INTO global_assistant_messages (id, thread_id, role, content, run_id, created_at) VALUES (:id, :threadId, :role, :content, :runId, :now)",
                Maps.of("id", id, "threadId", threadId, "role", role.name(), "content", content, "runId", runId, "now", Timestamp.from(now)));
        return new GlobalAssistantMessage(id, threadId, role, content, runId, now);
    }
    /**
     * Deterministic ordered read: created_at, id.
     */
    public List<GlobalAssistantMessage> findByThread(UUID threadId) {
        return jdbc.query(
                "SELECT * FROM global_assistant_messages WHERE thread_id = :threadId ORDER BY created_at, id",
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

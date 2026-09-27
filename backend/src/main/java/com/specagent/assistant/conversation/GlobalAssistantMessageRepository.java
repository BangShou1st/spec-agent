package com.specagent.assistant.conversation;

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
 * 文件名:GlobalAssistantMessageRepository.java
 *
 * 用途:面向用户消息的追加写入与确定性有序读取,对应
 * global_assistant_messages 表。
 *
 * 角色:conversation 包的消息存储层。会话的规范顺序是每线程单调递增
 * 的 append 序号(sequence),created_at 仅作为时间元数据,不参与排序。
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
                rs.getLong("sequence"),
                rs.getString("provider_label"),
                rs.getString("model_id"));
    }
    /**
     * 以每线程下一个序号追加一条消息。线程行锁(FOR UPDATE)把同一
     * 线程的并发追加串行化,后追加的消息必然观察到更大的序号;事务
     * 回滚则不会留下任何行。回滚导致的序号空洞无害:契约只关心顺序。
     */
    @org.springframework.transaction.annotation.Transactional
    public GlobalAssistantMessage append(UUID threadId, GlobalAssistantMessage.Role role, String content, UUID runId) {
        return append(threadId, role, content, runId, null, null);
    }

    /** 追加消息,可附带模型计费归属({@code providerLabel}/{@code modelId})。 */
    @org.springframework.transaction.annotation.Transactional
    public GlobalAssistantMessage append(UUID threadId, GlobalAssistantMessage.Role role, String content, UUID runId,
            String providerLabel, String modelId) {
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
                "INSERT INTO global_assistant_messages (id, thread_id, role, content, run_id, created_at, sequence, provider_label, model_id) VALUES (:id, :threadId, :role, :content, :runId, :now, :sequence, :providerLabel, :modelId)",
                Maps.of("id", id, "threadId", threadId, "role", role.name(), "content", content, "runId", runId,
                        "now", Timestamp.from(now), "sequence", next,
                        "providerLabel", providerLabel, "modelId", modelId));
        return new GlobalAssistantMessage(id, threadId, role, content, runId, now, next, providerLabel, modelId);
    }
    /**
     * 按会话的规范顺序(仅 append 序号)读取线程全部消息。
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

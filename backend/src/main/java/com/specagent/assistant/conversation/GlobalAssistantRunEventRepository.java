package com.specagent.assistant.conversation;

import com.specagent.common.Ids;
import com.specagent.common.Json;
import com.specagent.common.Maps;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 文件名:GlobalAssistantRunEventRepository.java
 *
 * 用途:Run 事件的串行化追加与按序号读取,对应
 * global_assistant_run_events 表(payload 以 jsonb 存储)。
 *
 * 角色:conversation 包的事件存储层。runtime、取消、终态事件都走
 * 同一条追加路径;每次追加先 FOR UPDATE 锁 Run 行再算下一个序号。
 * 不引入 Redis/Kafka/全局序号,顺序完全由每 Run 序号保证。
 */
@Repository
public class GlobalAssistantRunEventRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final Json json;
    private final RowMapper<GlobalAssistantRunEvent> rowMapper;
    public GlobalAssistantRunEventRepository(NamedParameterJdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
        this.rowMapper = (rs, rowNum) -> new GlobalAssistantRunEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getInt("sequence"),
                rs.getString("type"),
                json.read(rs.getString("payload"), Map.class),
                rs.getTimestamp("created_at").toInstant());
    }
    /**
     * 追加一条事件。在同一事务里先 FOR UPDATE 锁 Run 行,使并发写者的
     * 序号分配相互串行。
     *
     * 负载在生成不可变副本前先剔除 null 值:此前一个携带 null 值的事件
     * (例如未设置的 resultKind)会在工具已经成功执行之后,因
     * {@code Map.copyOf} 抛 NPE 而炸掉整个 Run——这是最糟糕的失败模式。
     * "键不存在"才是诚实的信号,所以 null 值条目直接丢弃,绝不入库。
     */
    @org.springframework.transaction.annotation.Transactional
    public GlobalAssistantRunEvent append(UUID runId, String type, Map<String, Object> payload) {
        List<UUID> locked = jdbc.queryForList(
                "SELECT id FROM global_assistant_runs WHERE id = :runId FOR UPDATE",
                Maps.of("runId", runId), UUID.class);
        if (locked.isEmpty()) {
            throw new IllegalArgumentException("Global assistant run not found: " + runId);
        }
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sequence), 0) FROM global_assistant_run_events WHERE run_id = :runId",
                Maps.of("runId", runId), Integer.class);
        int next = (max == null ? 0 : max) + 1;
        UUID id = Ids.random();
        Instant now = Instant.now();
        Map<String, Object> stored = sanitizePayload(payload);
        jdbc.update(
                "INSERT INTO global_assistant_run_events (id, run_id, sequence, type, payload, created_at) VALUES (:id, :runId, :seq, :type, CAST(:payload AS jsonb), :now)",
                Maps.of("id", id, "runId", runId, "seq", next, "type", type,
                        "payload", json.write(stored), "now", Timestamp.from(now)));
        return new GlobalAssistantRunEvent(id, runId, next, type, Map.copyOf(stored), now);
    }

    private static Map<String, Object> sanitizePayload(Map<String, Object> payload) {
        if (payload == null) {
            return Map.of();
        }
        Map<String, Object> clean = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, Object> entry : payload.entrySet()) {
            if (entry.getValue() != null) {
                clean.put(entry.getKey(), entry.getValue());
            }
        }
        return clean;
    }
    public List<GlobalAssistantRunEvent> findByRun(UUID runId) {
        return jdbc.query("SELECT * FROM global_assistant_run_events WHERE run_id = :runId ORDER BY sequence",
                Maps.of("runId", runId), rowMapper);
    }
    public List<GlobalAssistantRunEvent> findAfter(UUID runId, int cursor) {
        return jdbc.query(
                "SELECT * FROM global_assistant_run_events WHERE run_id = :runId AND sequence > :cursor ORDER BY sequence",
                Maps.of("runId", runId, "cursor", cursor), rowMapper);
    }
}

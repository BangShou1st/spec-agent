package com.specagent.agent.runevent;

import com.specagent.common.Json;
import com.specagent.common.Maps;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:AgentRunEventRepository.java
 *
 * 用途:{@code agent_run_events} 表的只追加(append-only)持久化。
 *
 * 约束:事件绝不更新或删除;每次运行的 sequence 在插入时原子分配,
 * 保证并发写入者不会交错编号。
 */
@Repository
public class AgentRunEventRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Json json;
    private final RowMapper<AgentRunEvent> rowMapper;

    public AgentRunEventRepository(NamedParameterJdbcTemplate jdbcTemplate, Json json) {
        this.jdbcTemplate = jdbcTemplate;
        this.json = json;
        this.rowMapper = (rs, rowNum) -> new AgentRunEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getInt("sequence"),
                AgentRunPhase.fromCode(rs.getString("phase")),
                rs.getString("event_type"),
                json.read(rs.getString("payload"), Map.class),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 追加事件并分配下一个 per-run sequence。
     *
     * <p><b>为什么必须在一个事务里"先锁后插"(多 Worker 正确性)。</b>
     * {@code MAX(sequence) + 1} 在 Read Committed 下读的是<b>语句快照</b>:两个并发
     * 事务会算出同一个下一个序号,后到的一方撞 {@code UNIQUE (run_id, sequence)} 而
     * 失败。单执行器时代每个 run 只有一个执行者,该竞争不可达;多 Worker 下接管
     * 窗口内则可能发生(旧执行者的迟到写入已被任务级护栏拒绝,但同一 run 的继任
     * 执行者与其自身的收尾路径仍可能并发),而事件序列是前端进度与恢复核验的事实源
     * ——丢一条或重号都会让读模型失真。
     *
     * <p>因此本方法在<b>一个事务内</b>先用 {@code SELECT ... FOR UPDATE} 锁住该 run
     * 的业务行,再分配序号并插入。{@code @Transactional} 是必需的,不是修饰:在
     * 自动提交下每条语句自成事务,{@code FOR UPDATE} 会在 INSERT 之前就释放,锁
     * 保护不了任何东西(这一点由 {@code AgentRunEventConcurrentAppendTest} 用真实
     * 并发反向验证过)。
     *
     * <p>锁粒度是 run 行,与全局锁顺序一致(task 行 → 容量槽 → scope 行 →
     * <b>业务 Run</b> → 业务行):调用方通常已经持有 run 行锁(状态写入),在同一
     * 事务内重复取锁是安全的;不同 run 之间互不阻塞,并行度不受影响。
     *
     * <p><b>冲突仍然对调用方可见。</b>这里只消除"同一个 run 上序号竞争"这一种冲突
     * 来源,绝不吞掉其他唯一性冲突:create-run 幂等仍要求数据库竞争的胜者追加
     * RUN_CREATED,落败方必须看到异常而不是被静默忽略。
     */
    /**
     * 只属于"执行周期进度"的事件类型:run 终态之后绝不允许再追加。
     *
     * <p>终态事件本身(RUN_COMPLETED/RUN_FAILED/RUN_CANCELLED/RESPOND_MESSAGE/
     * AWAITING_APPROVAL)以及用户驱动的后续事件(ACCEPTANCE_EXECUTED、续跑请求)
     * <b>不在此列</b>——它们合法地发生在终态之后,绝不误杀。
     */
    private static final java.util.Set<String> CYCLE_PROGRESS_TYPES = java.util.Set.of(
            "SNAPSHOT_BUILT", "STATE_UPDATING", "STATE_UPDATE_STARTED", "STATE_UPDATED",
            "STATE_UPDATE_COMPLETED", "DECIDING", "DECISION_STARTED", "PROPOSAL_CREATED",
            "PROCESS_NOTE", "EXECUTING", "ARTIFACT_GENERATING");

    @Transactional
    public void append(AgentRunEvent event) {
        // 序号分配前的串行化点:锁住该 run 的业务行(run 行由外键保证存在)。
        String status = jdbcTemplate.queryForList(
                "SELECT status FROM agent_runs WHERE id = :runId FOR UPDATE",
                Maps.of("runId", event.runId()), String.class).stream().findFirst().orElse(null);
        // 终态闭合保护(独立于执行 token):已终态的 run 绝不能再接受"周期进度"
        // 事件——无论迟到路径是否携带 token,都无法绕过。它只作用于周期进度类型,
        // 因此终态事件本身与合法用户后续事件(如 ACCEPTANCE_EXECUTED)不受影响。
        if (isTerminal(status) && CYCLE_PROGRESS_TYPES.contains(event.eventType())) {
            throw new IllegalStateException(
                    "run " + event.runId() + " is already " + status
                            + "; refusing a late cycle event " + event.eventType());
        }
        String sql = """
                INSERT INTO agent_run_events (id, run_id, sequence, phase, event_type, payload, created_at)
                SELECT :id, :runId, COALESCE(MAX(sequence), 0) + 1, :phase, :eventType,
                       CAST(:payload AS jsonb), :createdAt
                FROM agent_run_events WHERE run_id = :runId
                """;
        jdbcTemplate.update(sql, Maps.of(
                "id", event.id(),
                "runId", event.runId(),
                "phase", event.phase().code(),
                "eventType", event.eventType(),
                "payload", json.write(event.payload() == null ? Map.of() : event.payload()),
                "createdAt", Timestamp.from(event.createdAt())));
    }

    private static boolean isTerminal(String status) {
        return "completed".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }

    public List<AgentRunEvent> findByRunId(UUID runId) {
        String sql = """
                SELECT * FROM agent_run_events WHERE run_id = :runId ORDER BY sequence
                """;
        return jdbcTemplate.query(sql, Maps.of("runId", runId), rowMapper);
    }
}

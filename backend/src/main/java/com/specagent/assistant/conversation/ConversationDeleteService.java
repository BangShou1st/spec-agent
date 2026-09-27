package com.specagent.assistant.conversation;

import com.specagent.common.Maps;
import com.specagent.assistant.conversation.GlobalAssistantRunRepository;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文件名:ConversationDeleteService.java
 *
 * 用途:会话删除的唯一入口,负责在单个事务里清空一个线程相关的全部
 * 会话数据:Run 事件、能力调用记录、待处理轮次(PendingTurn)、消息、
 * Run,最后删除线程本身。
 *
 * 角色:conversation 包的删除兜底服务。只删 GA 侧的会话数据;产品侧
 * 已产生的持久化副作用(如创建出的项目)不会被回滚。删除前校验线程上
 * 没有活跃 Run 和未决轮次,否则以 THREAD_ACTIVE 拒绝删除。
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

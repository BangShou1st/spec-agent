package com.specagent.assistant.conversation;

import com.specagent.access.AccessDeniedException;
import com.specagent.access.AccessMode;
import com.specagent.access.AccessReason;
import com.specagent.access.ActorContext;
import com.specagent.access.IdentityModeProperties;
import com.specagent.access.OwnershipAttribution;
import com.specagent.common.Ids;
import com.specagent.common.Json;
import com.specagent.common.Maps;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 文件名:GlobalAssistantThreadRepository.java
 *
 * 用途:线程的持久化,对应 global_assistant_threads 表,支持带版本号
 * 的工作状态与摘要更新(乐观 CAS)。
 *
 * 角色:conversation 包的线程存储层。版本化更新采用 fail-closed 策略:
 * 期望版本不匹配时直接抛 GlobalAssistantVersionConflictException,绝不
 * 静默覆盖并发写入。
 *
 * <b>第二阶段(身份/权限)</b>:创建会话时必须落 {@code org_id} 与
 * {@code owner_user_id}。会话默认私有,而"私有"的定义就是这两个列;
 * 漏写归属会让会话对所有人为不可见(失败关闭方向),或在只按组织过滤的
 * 读路径上变成全组织可见(泄露方向)——因此这里在插入前显式校验归属可用,
 * 缺组织选择直接拒绝,绝不写入一行没有主人的会话。
 */
@Repository
public class GlobalAssistantThreadRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final Json json;
    private final IdentityModeProperties properties;
    private final RowMapper<GlobalAssistantThread> rowMapper;

    public GlobalAssistantThreadRepository(NamedParameterJdbcTemplate jdbc, Json json,
                                           IdentityModeProperties properties) {
        this.jdbc = jdbc;
        this.json = json;
        this.properties = properties;
        this.rowMapper = (rs, rowNum) -> new GlobalAssistantThread(
                rs.getObject("id", UUID.class),
                rs.getString("summary"),
                rs.getInt("summary_version"),
                rs.getString("working_state"),
                rs.getInt("working_state_version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    public GlobalAssistantThread create() {
        UUID id = Ids.random();
        Instant now = Instant.now();
        ActorContext owner = requireOwnership();
        jdbc.update(
                "INSERT INTO global_assistant_threads (id, summary, summary_version, working_state, "
                        + "working_state_version, org_id, owner_user_id, created_at, updated_at) "
                        + "VALUES (:id, NULL, 0, NULL, 0, :orgId, :ownerUserId, :now, :now)",
                Maps.of("id", id,
                        "orgId", owner.orgId(),
                        "ownerUserId", owner.userId(),
                        "now", Timestamp.from(now)));
        return findById(id).orElseThrow();
    }

    /**
     * 创建会话所需的归属主体。多组织用户尚未选择组织时明确拒绝
     * (原因码 {@code ORG_SELECTION_REQUIRED}),而不是写入一行没有组织归属
     * 的会话。
     */
    private ActorContext requireOwnership() {
        AccessMode mode = properties.accessMode();
        ActorContext actor = OwnershipAttribution.currentOrDefault(mode);
        if (actor.orgId() == null || actor.userId() == null) {
            throw AccessDeniedException.forbidden(AccessReason.ORG_SELECTION_REQUIRED,
                    "An organization must be selected before creating a conversation");
        }
        return actor;
    }

    public Optional<GlobalAssistantThread> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.getJdbcOperations().queryForObject(
                    "SELECT * FROM global_assistant_threads WHERE id = ?", rowMapper, id));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }
    /**
     * 带版本号的工作状态更新:版本冲突时 fail-closed(直接抛异常)。
     */
    public void updateWorkingState(UUID threadId, Map<String, Object> workingState, int expectedVersion) {
        String sql = "UPDATE global_assistant_threads SET working_state = CAST(:state AS jsonb), working_state_version = working_state_version + 1, updated_at = :now WHERE id = :id AND working_state_version = :expected";
        int updated = jdbc.update(sql, Maps.of(
                "id", threadId,
                "state", json.write(workingState == null ? Map.of() : workingState),
                "now", Timestamp.from(Instant.now()),
                "expected", expectedVersion));
        if (updated != 1) {
            throw new GlobalAssistantVersionConflictException("WorkingState version conflict for thread: " + threadId);
        }
    }
    /**
     * 带版本号的摘要更新:版本冲突时 fail-closed(直接抛异常)。
     */
    public void updateSummary(UUID threadId, String summary, int expectedVersion) {
        String sql = "UPDATE global_assistant_threads SET summary = :summary, summary_version = summary_version + 1, updated_at = :now WHERE id = :id AND summary_version = :expected";
        int updated = jdbc.update(sql, Maps.of(
                "id", threadId,
                "summary", summary,
                "now", Timestamp.from(Instant.now()),
                "expected", expectedVersion));
        if (updated != 1) {
            throw new GlobalAssistantVersionConflictException("Summary version conflict for thread: " + threadId);
        }
    }
}

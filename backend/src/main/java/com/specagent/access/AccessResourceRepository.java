package com.specagent.access;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 文件名:AccessResourceRepository.java
 *
 * 用途:授权判定需要的<b>读</b>事实:用户状态、组织状态、成员资格。
 *
 * 有意只做读取。组织/用户/成员的写入属于身份管理,住在
 * {@code com.specagent.access.identity};这样 {@link AuthorizationGuard}
 * 依赖的只有读事实,而身份服务依赖本类,依赖方向保持
 * identity -> root(反向边不存在)。
 *
 * 每个查询都直接按主键取行,不做"先列表再内存过滤";授权判定不允许
 * 在应用层做二次筛选,否则过滤条件会被后续改动漏掉。
 */
@Repository
public class AccessResourceRepository {
    public Optional<UUID> gaRunThread(UUID runId) {
        return jdbc.query("SELECT thread_id FROM global_assistant_runs WHERE id=:id",
                Map.of("id",runId),(rs,i)->rs.getObject("thread_id",UUID.class)).stream().findFirst();
    }

    private final NamedParameterJdbcTemplate jdbc;

    public AccessResourceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record UserFacts(UUID id, String displayName, String status) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    public record OrgFacts(UUID id, String name, String kind, String status) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    public record MembershipFacts(
            UUID orgId,
            String orgName,
            OrgRole role,
            String membershipStatus,
            String orgStatus) {

        public boolean active() {
            return "ACTIVE".equals(membershipStatus);
        }
    }

    public Optional<UserFacts> findUser(UUID userId) {
        return jdbc.query("""
                SELECT id, display_name, status FROM app_users WHERE id = :id
                """, Map.of("id", userId),
                (rs, rowNum) -> new UserFacts(
                        rs.getObject("id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("status")))
                .stream().findFirst();
    }

    public Optional<OrgFacts> findOrganization(UUID orgId) {
        return jdbc.query("""
                SELECT id, name, kind, status FROM organizations WHERE id = :id
                """, Map.of("id", orgId),
                (rs, rowNum) -> new OrgFacts(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("kind"),
                        rs.getString("status")))
                .stream().findFirst();
    }

    public Optional<MembershipFacts> findMembership(UUID orgId, UUID userId) {
        return jdbc.query("""
                SELECT m.org_id, o.name AS org_name, m.role, m.status AS membership_status,
                       o.status AS org_status
                FROM organization_members m
                JOIN organizations o ON o.id = m.org_id
                WHERE m.org_id = :orgId AND m.user_id = :userId
                """, Map.of("orgId", orgId, "userId", userId), this::mapMembership)
                .stream().findFirst();
    }

    /**
     * 主体的全部有效成员资格。用于解析"未显式选择组织"时的默认组织:
     * 恰好一个 -> 直接采用;零个或多个 -> 要求显式选择(绝不猜)。
     */
    public List<MembershipFacts> findActiveMemberships(UUID userId) {
        return jdbc.query("""
                SELECT m.org_id, o.name AS org_name, m.role, m.status AS membership_status,
                       o.status AS org_status
                FROM organization_members m
                JOIN organizations o ON o.id = m.org_id
                WHERE m.user_id = :userId AND m.status = 'ACTIVE' AND o.status = 'ACTIVE'
                ORDER BY o.created_at, m.org_id
                """, Map.of("userId", userId), this::mapMembership);
    }

    /** 组织内有效 OWNER 数——最后一个 OWNER 不允许退出/降级的判定依据。 */
    public int countActiveOwners(UUID orgId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM organization_members
                WHERE org_id = :orgId AND role = 'OWNER' AND status = 'ACTIVE'
                """, Map.of("orgId", orgId), Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 组织是否存在活跃成员(用于"成员资格快照"与审计)。
     * 这不是授权判定,只是管理面的可见性计数。
     */
    public int countActiveMembers(UUID orgId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM organization_members
                WHERE org_id = :orgId AND status = 'ACTIVE'
                """, Map.of("orgId", orgId), Integer.class);
        return count == null ? 0 : count;
    }

    private MembershipFacts mapMembership(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new MembershipFacts(
                rs.getObject("org_id", UUID.class),
                rs.getString("org_name"),
                OrgRole.parse(rs.getString("role")),
                rs.getString("membership_status"),
                rs.getString("org_status"));
    }

    // ------------------------------------------------- 资源归属解析(§1.1 第 4 条)

    /**
     * 项目的所属组织。归属必须由父关系推导,不接受调用方传入。
     * 返回 empty 表示项目不存在<b>或</b>归属未确定——两种情形对外都必须是
     * "不可见",绝不区分。
     */
    public Optional<UUID> projectOrg(UUID projectId) {
        return jdbc.query("""
                SELECT org_id FROM projects WHERE id = :id
                """, Map.of("id", projectId),
                (rs, rowNum) -> rs.getObject("org_id", UUID.class)).stream()
                .filter(java.util.Objects::nonNull).findFirst();
    }

    /** 画像的所属组织。项目默认画像的"同组织"约束据此核验(冻结决定 #2)。 */
    public Optional<UUID> profileOrg(UUID profileId) {
        return jdbc.query("""
                SELECT org_id FROM profiles WHERE id = :id
                """, Map.of("id", profileId),
                (rs, rowNum) -> rs.getObject("org_id", UUID.class)).stream().findFirst();
    }

    /** 主体在某项目上的角色;无成员资格返回 empty。 */
    public Optional<ProjectRole> projectRole(UUID projectId, UUID userId) {
        return jdbc.query("""
                SELECT role FROM project_members
                WHERE project_id = :projectId AND user_id = :userId
                """, Map.of("projectId", projectId, "userId", userId),
                (rs, rowNum) -> ProjectRole.parse(rs.getString("role"))).stream().findFirst();
    }

    /** GA 会话的归属与属主。私有会话的判定依据。 */
    public Optional<ThreadFacts> threadFacts(UUID threadId) {        return jdbc.query("""
                SELECT org_id, owner_user_id FROM global_assistant_threads WHERE id = :id
                """, Map.of("id", threadId),
                (rs, rowNum) -> new ThreadFacts(
                        rs.getObject("org_id", UUID.class),
                        rs.getObject("owner_user_id", UUID.class))).stream().findFirst();
    }

    public record ThreadFacts(UUID orgId, UUID ownerUserId) {
    }

    /** 供身份服务转发的通用时间戳读取(避免在 identity 包重复 JDBC 细节)。 */
    static Timestamp sqlTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    /** 空集合安全的 IN 参数构造:避免 (NULL) 造成的全表扫描。 */
    static Map<String, Object> inClause(String name, Collection<?> values) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(name, List.copyOf(values));
        return params;
    }
}

package com.specagent.access.identity;

import com.specagent.access.OrgRole;
import com.specagent.access.ProjectRole;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 文件名:IdentityRepository.java
 *
 * 用途:身份与组织事实的<b>写</b>侧(读侧在
 * {@link com.specagent.access.AccessResourceRepository})。
 *
 * 全部写入都是幂等的({@code ON CONFLICT DO NOTHING} /
 * {@code DO UPDATE}),因为调用点包括登录回调与本地模式引导——两者都会
 * 在并发首登时重放。身份解析刻意使用由 (issuer, subject) 派生的确定性
 * user id,使并发首登的两个事务算出<b>同一个</b> user id,从而既不产生
 * 孤儿用户行,也不需要分布式锁。
 */
@Repository
public class IdentityRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public IdentityRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> findUserIdByIssuerSubject(String issuer, String subject) {
        return jdbc.query("""
                SELECT user_id FROM user_identities WHERE issuer = :issuer AND subject = :subject
                """, Map.of("issuer", issuer, "subject", subject),
                (rs, rowNum) -> rs.getObject("user_id", UUID.class)).stream().findFirst();
    }

    public void insertUserIfAbsent(UUID userId, String displayName) {
        jdbc.update("""
                INSERT INTO app_users (id, display_name)
                VALUES (:id, :displayName)
                ON CONFLICT (id) DO NOTHING
                """, Map.of("id", userId, "displayName", displayName));
    }

    public void insertIdentityIfAbsent(UUID identityId, UUID userId, String issuer, String subject) {
        jdbc.update("""
                INSERT INTO user_identities (id, user_id, issuer, subject)
                VALUES (:id, :userId, :issuer, :subject)
                ON CONFLICT (issuer, subject) DO NOTHING
                """, Map.of("id", identityId, "userId", userId,
                "issuer", issuer, "subject", subject));
    }

    public void insertOrganizationIfAbsent(UUID orgId, String name, String kind) {
        jdbc.update("""
                INSERT INTO organizations (id, name, kind)
                VALUES (:id, :name, :kind)
                ON CONFLICT (id) DO NOTHING
                """, Map.of("id", orgId, "name", name, "kind", kind));
    }

    public void upsertMembership(UUID orgId, UUID userId, OrgRole role) {
        jdbc.update("""
                INSERT INTO organization_members (org_id, user_id, role, status)
                VALUES (:orgId, :userId, :role, 'ACTIVE')
                ON CONFLICT (org_id, user_id)
                DO UPDATE SET role = EXCLUDED.role, status = 'ACTIVE', updated_at = now()
                """, Map.of("orgId", orgId, "userId", userId, "role", role.name()));
    }

    /**
     * 授予/更新项目角色。{@code org_id} 一并写入,与
     * {@code project_members(project_id, org_id) -> projects(id, org_id)}
     * 复合外键共同阻止跨组织授予。
     */
    public void upsertProjectMember(UUID projectId, UUID userId, UUID orgId, ProjectRole role) {
        jdbc.update("""
                INSERT INTO project_members (project_id, user_id, org_id, role)
                VALUES (:projectId, :userId, :orgId, :role)
                ON CONFLICT (project_id, user_id)
                DO UPDATE SET role = EXCLUDED.role, org_id = EXCLUDED.org_id, updated_at = now()
                """, Map.of("projectId", projectId, "userId", userId,
                "orgId", orgId, "role", role.name()));
    }

    public void updateMembershipStatus(UUID orgId, UUID userId, String status) {
        jdbc.update("""
                UPDATE organization_members SET status = :status, updated_at = now()
                WHERE org_id = :orgId AND user_id = :userId
                """, Map.of("orgId", orgId, "userId", userId, "status", status));
    }

    /**
     * 用户级撤权:一次停用其全部组织成员资格。
     *
     * 这是"用户被禁用"与"组织资格"两条事实的收敛点——用户禁用必须在每次
     * 授权检查生效,所以两条事实必须一起改,不能只改 app_users.status。
     */
    public void updateMembershipStatusForAllOrgs(UUID userId, String status) {
        jdbc.update("""
                UPDATE organization_members SET status = :status, updated_at = now()
                WHERE user_id = :userId
                """, Map.of("userId", userId, "status", status));
    }

    public void updateUserStatus(UUID userId, String status) {
        jdbc.update("""
                UPDATE app_users SET status = :status, updated_at = now() WHERE id = :id
                """, Map.of("id", userId, "status", status));
    }

    public void renameOrganization(UUID orgId, String name) {
        jdbc.update("""
                UPDATE organizations SET name = :name, updated_at = now() WHERE id = :id
                """, Map.of("id", orgId, "name", name));
    }
}

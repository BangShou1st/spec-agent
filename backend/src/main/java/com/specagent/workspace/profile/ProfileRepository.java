package com.specagent.workspace.profile;

import com.specagent.common.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:ProfileRepository.java
 *
 * 用途:Profile 的 JDBC 仓储,负责画像的读写:按 id/name 查询与
 * 全量列表;aspects、spec_section_definitions、question_policy_hints
 * 三个列表字段以 JSON 存储,由 {@code Json} 辅助类序列化/反序列化。
 */
@Repository
public class ProfileRepository {
    private com.specagent.access.OrganizationConfigurationScope scope;
    @org.springframework.beans.factory.annotation.Autowired
    public void setOrganizationScope(com.specagent.access.OrganizationConfigurationScope scope) {this.scope=scope;}

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Json json;
    private final RowMapper<Profile> rowMapper;

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    public ProfileRepository(NamedParameterJdbcTemplate jdbcTemplate, Json json) {
        this.jdbcTemplate = jdbcTemplate;
        this.json = json;
        this.rowMapper = (rs, rowNum) -> new Profile(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("description"),
                json.readList(rs.getString("aspects"), STRING_LIST),
                json.readList(rs.getString("spec_section_definitions"), STRING_LIST),
                json.readList(rs.getString("question_policy_hints"), STRING_LIST),
                rs.getString("tone"),
                rs.getTimestamp("created_at").toInstant());
    }

    public Optional<Profile> findById(UUID id) {
        String sql = "SELECT * FROM profiles WHERE id = :id AND org_id=:org";
        return jdbcTemplate.query(sql, Map.of("id", id,"org",scope.readOrg()), rowMapper).stream().findFirst();
    }

    public Optional<Profile> findByName(String name) {
        String sql = "SELECT * FROM profiles WHERE name = :name AND org_id=:org";
        return jdbcTemplate.query(sql, Map.of("name", name,"org",scope.readOrg()), rowMapper).stream().findFirst();
    }

    public List<Profile> findAll() {
        String sql = "SELECT * FROM profiles WHERE org_id=:org ORDER BY name";
        return jdbcTemplate.query(sql, Map.of("org",scope.readOrg()), rowMapper);
    }

    /**
     * 组织内的默认画像 id;该组织还没有画像时,从模板行复制一份。
     *
     * 存在的理由(冻结决定 #2):{@code profiles} 是<b>组织级</b>资源,
     * 并且数据库上用复合外键 {@code projects(default_profile_id, org_id) ->
     * profiles(id, org_id)} 强制"项目只能引用同组织画像"。因此给一个新组织
     * 建项目之前,该组织必须已经有画像行——否则插入会被外键拒绝。
     *
     * 复制的是配置内容(方面、规格章节定义、提问策略、语气),不是归属:
     * 新行属于目标组织,与模板组织无关。
     */
    public UUID ensureOrgDefaultProfile(UUID orgId, UUID templateProfileId) {
        if (!scope.readOrg().equals(orgId))
            throw com.specagent.access.AccessDeniedException.notVisible("Organization profile not found");
        if (!ProfileService.DEFAULT_PROFILE_ID.equals(templateProfileId))
            throw new IllegalArgumentException("Only the installation's built-in profile can be copied");
        Optional<UUID> existing = firstProfileIdInOrg(orgId);
        if (existing.isPresent()) {
            return existing.get();
        }
        jdbcTemplate.update("""
                INSERT INTO profiles (id, name, description, aspects, spec_section_definitions,
                                      question_policy_hints, tone, org_id)
                SELECT :id, name, description, aspects, spec_section_definitions,
                       question_policy_hints, tone, :orgId
                FROM profiles WHERE id = :templateId AND org_id=:templateOrg
                ON CONFLICT (org_id, name) DO NOTHING
                """, Map.of("id", UUID.randomUUID(), "orgId", orgId, "templateId", templateProfileId,
                        "templateOrg",com.specagent.access.LocalPrincipalIds.ORG_ID));
        return firstProfileIdInOrg(orgId)
                .orElseThrow(() -> new IllegalStateException(
                        "No profile could be resolved for organization " + orgId
                                + "; the template profile is missing"));
    }

    private Optional<UUID> firstProfileIdInOrg(UUID orgId) {
        String sql = "SELECT id FROM profiles WHERE org_id = :orgId ORDER BY created_at, id LIMIT 1";
        return jdbcTemplate.query(sql, Map.of("orgId", orgId),
                (rs, rowNum) -> rs.getObject("id", UUID.class)).stream().findFirst();
    }
}

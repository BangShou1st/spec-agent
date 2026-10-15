package com.specagent.skill.persistence;

import com.specagent.common.Json;
import com.specagent.skill.access.SkillAccess;
import org.springframework.transaction.annotation.Transactional;
import com.specagent.skill.domain.Skill;
import com.specagent.skill.domain.SkillPackageFile;
import com.specagent.skill.domain.SkillSourceKind;
import com.specagent.skill.domain.SkillStagedImport;
import com.specagent.skill.domain.SkillVersion;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:SkillRepository.java
 *
 * 用途:Skill Runtime 的持久化存储。数据库是 Skill 状态的权威来源:已安装
 * 版本为带内容哈希的不可变记录,暂存导入是独立的待审阅记录。任何东西都不落
 * 宿主文件系统。
 *
 * 本仓储刻意作为 Skill 领域的持久化接缝存在;Agent/Brain 绝不直接消费它。
 */
@Repository
@Transactional
public class SkillRepository {

    private static final String SKILL_COLUMNS = """
            id, skill_id, name, description, source_kind, source_identity,
            current_version_id, enabled, created_at, updated_at""";

    private final NamedParameterJdbcTemplate jdbc;
    private final Json json;
    private final SkillAccess access;

    private final RowMapper<Skill> skillMapper = (rs, rowNum) -> new Skill(
            rs.getObject("id", UUID.class),
            rs.getString("skill_id"),
            rs.getString("name"),
            rs.getString("description"),
            SkillSourceKind.fromCode(rs.getString("source_kind")),
            rs.getString("source_identity"),
            rs.getObject("current_version_id", UUID.class),
            rs.getBoolean("enabled"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")));

    private static final String VERSION_COLUMNS = """
            id, skill_row_id, version_no, content_hash, manifest, instructions,
            source_identity, file_count, total_bytes, created_at""";

    private final RowMapper<SkillVersion> versionMapper = (rs, rowNum) -> new SkillVersion(
            rs.getObject("id", UUID.class),
            rs.getObject("skill_row_id", UUID.class),
            rs.getInt("version_no"),
            rs.getString("content_hash"),
            rs.getString("manifest"),
            rs.getString("instructions"),
            rs.getString("source_identity"),
            rs.getInt("file_count"),
            rs.getLong("total_bytes"),
            toInstant(rs.getTimestamp("created_at")));

    private final RowMapper<SkillPackageFile> fileMapper = (rs, rowNum) -> new SkillPackageFile(
            rs.getObject("id", UUID.class),
            rs.getObject("version_id", UUID.class),
            rs.getString("relative_path"),
            SkillPackageFile.FileKind.fromCode(rs.getString("kind")),
            rs.getLong("size_bytes"),
            rs.getString("sha256"),
            rs.getBytes("content"));

    private final RowMapper<SkillStagedImport> stagedMapper = (rs, rowNum) -> new SkillStagedImport(
            rs.getObject("id", UUID.class),
            SkillSourceKind.fromCode(rs.getString("source_kind")),
            rs.getString("source_identity"),
            rs.getString("manifest"),
            rs.getString("file_entries"),
            rs.getLong("total_bytes"),
            rs.getInt("file_count"),
            rs.getString("content_hash"),
            SkillStagedImport.Status.valueOf(rs.getString("status")),
            rs.getString("rejected_reason"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("installed_at")));

    public SkillRepository(NamedParameterJdbcTemplate jdbc, Json json, SkillAccess access) {
        this.jdbc = jdbc;
        this.json = json;
        this.access = access;
    }

    // ---- skill 表 ---------------------------------------------------------

    public Skill insertSkill(Skill skill) {
        access.manage();
        String sql = """
                INSERT INTO skills
                    (id, org_id, skill_id, name, description, source_kind, source_identity,
                     current_version_id, enabled, created_at, updated_at)
                VALUES
                    (:id, :actorOrg, :skillId, :name, :description, :sourceKind, :sourceIdentity,
                     :currentVersionId, :enabled, :createdAt, :updatedAt)
                """;
        jdbc.update(sql, new MapSqlParameterSource(params())
                .addValue("id", skill.id())
                .addValue("skillId", skill.skillId())
                .addValue("name", skill.name())
                .addValue("description", skill.description())
                .addValue("sourceKind", skill.sourceKind().code())
                .addValue("sourceIdentity", skill.sourceIdentity())
                .addValue("currentVersionId", skill.currentVersionId())
                .addValue("enabled", skill.enabled())
                .addValue("createdAt", Timestamp.from(skill.createdAt()))
                .addValue("updatedAt", Timestamp.from(skill.updatedAt())));
        return skill;
    }

    public Optional<Skill> findSkill(String skillId) {
        String sql = "SELECT " + SKILL_COLUMNS + " FROM skills WHERE org_id=:actorOrg AND skill_id = :skillId";
        return jdbc.query(sql, params("skillId", skillId), skillMapper).stream().findFirst();
    }

    public Optional<Skill> findSkillById(UUID id) {
        String sql = "SELECT " + SKILL_COLUMNS + " FROM skills WHERE org_id=:actorOrg AND id = :id";
        return jdbc.query(sql, params("id", id), skillMapper).stream().findFirst();
    }

    public List<Skill> listSkills() {
        String sql = "SELECT " + SKILL_COLUMNS + " FROM skills WHERE org_id=:actorOrg ORDER BY name";
        return jdbc.query(sql, params(), skillMapper);
    }

    public void updateSkillEnabled(UUID id, boolean enabled) {
        requireManagedSkill(id);
        String sql = """
                UPDATE skills SET enabled = :enabled, updated_at = :updatedAt WHERE org_id=:actorOrg AND id = :id
                """;
        jdbc.update(sql, new MapSqlParameterSource(params())
                .addValue("id", id)
                .addValue("enabled", enabled)
                .addValue("updatedAt", Timestamp.from(Instant.now())));
    }

    public void updateSkillCurrentVersion(UUID id, UUID versionId, String description) {
        requireManagedSkill(id);
        requireVersionParent(id, versionId);
        String sql = """
                UPDATE skills
                SET current_version_id = :versionId, description = :description,
                    updated_at = :updatedAt
                WHERE org_id=:actorOrg AND id = :id
                """;
        jdbc.update(sql, new MapSqlParameterSource(params())
                .addValue("id", id)
                .addValue("versionId", versionId)
                .addValue("description", description)
                .addValue("updatedAt", Timestamp.from(Instant.now())));
    }

    public void deleteSkill(UUID id) {
        requireManagedSkill(id);
        jdbc.update("DELETE FROM skills WHERE org_id=:actorOrg AND id = :id", params("id", id));
    }

    public Optional<Skill> findEnabledSkillByExactName(String name) {
        String sql = "SELECT " + SKILL_COLUMNS
                + " FROM skills WHERE org_id=:actorOrg AND name = :name AND enabled = true";
        return jdbc.query(sql, params("name", name), skillMapper).stream().findFirst();
    }

    public void requireRunExecution(UUID projectId, UUID runId) {
        access.execution(projectId,runId);
    }

    public Optional<Skill> findEnabledSkillForActivation(String skillId) {
        String sql="SELECT " + SKILL_COLUMNS + " FROM skills WHERE org_id=:actorOrg AND skill_id=:skillId"
                + " AND enabled=true AND current_version_id IS NOT NULL FOR SHARE";
        return jdbc.query(sql,params("skillId",skillId),skillMapper).stream().findFirst();
    }

    public boolean hasVerifiedActivation(UUID projectId, UUID runId, UUID versionId) {
        access.execution(projectId,runId);
        return !jdbc.queryForList("""
                WITH RECURSIVE allowed_runs(id,parent_id,path) AS (
                  SELECT r.id,CASE WHEN r.trigger_type='continue_cycle' THEN r.parent_run_id END,ARRAY[r.id]
                  FROM agent_runs r JOIN workspace_run_authorizations d ON d.run_id=r.id
                  WHERE r.id=:runId AND r.project_id=:projectId AND d.actor_user_id=:actorUser
                    AND d.actor_org_id=:actorOrg AND d.actor_source=:actorSource
                  UNION ALL
                  SELECT p.id,CASE WHEN p.trigger_type='continue_cycle' THEN p.parent_run_id END,c.path || p.id
                  FROM allowed_runs c JOIN agent_runs p ON p.id=c.parent_id
                  JOIN workspace_run_authorizations d ON d.run_id=p.id
                  WHERE p.project_id=:projectId AND d.actor_user_id=:actorUser
                    AND d.actor_org_id=:actorOrg AND d.actor_source=:actorSource
                    AND NOT(p.id=ANY(c.path)) AND cardinality(c.path)<64
                )
                SELECT s.id FROM skill_activations a
                JOIN skill_versions v ON v.id=a.version_id
                JOIN skills s ON s.id=v.skill_row_id
                WHERE a.org_id=:actorOrg AND s.org_id=:actorOrg AND s.enabled=true
                  AND a.project_id=:projectId AND a.verified_run_id IN (SELECT id FROM allowed_runs)
                  AND a.run_id=a.verified_run_id
                  AND a.actor_user_id=:actorUser AND a.actor_source=:actorSource AND a.version_id=:versionId
                LIMIT 1 FOR SHARE OF s
                """,params("projectId",projectId,"runId",runId,"versionId",versionId)).isEmpty();
    }

    // ---- 版本表 ------------------------------------------------------------

    public SkillVersion insertVersion(SkillVersion version) {
        requireManagedSkill(version.skillRowId());
        String sql = """
                INSERT INTO skill_versions
                    (id, skill_row_id, skill_id, version_no, content_hash, manifest, instructions,
                     source_identity, file_count, total_bytes, created_at)
                VALUES
                    (:id, :skillRowId, :parentSkillId, :versionNo, :contentHash, :manifest, :instructions,
                     :sourceIdentity, :fileCount, :totalBytes, :createdAt)
                """;
        jdbc.update(sql, new MapSqlParameterSource(params())
                .addValue("id", version.id())
                .addValue("skillRowId", version.skillRowId())
                .addValue("parentSkillId", findSkillById(version.skillRowId()).orElseThrow().skillId())
                .addValue("versionNo", version.versionNo())
                .addValue("contentHash", version.contentHash())
                .addValue("manifest", version.manifest())
                .addValue("instructions", version.instructions())
                .addValue("sourceIdentity", version.sourceIdentity())
                .addValue("fileCount", version.fileCount())
                .addValue("totalBytes", version.totalBytes())
                .addValue("createdAt", Timestamp.from(version.createdAt())));
        return version;
    }

    public Optional<SkillVersion> findVersion(UUID id) {
        String sql = "SELECT " + VERSION_COLUMNS + " FROM skill_versions WHERE skill_row_id IN (SELECT id FROM skills WHERE org_id=:actorOrg) AND id = :id";
        return jdbc.query(sql, params("id", id), versionMapper).stream().findFirst();
    }

    public Optional<SkillVersion> findVersionByContentHash(String contentHash) {
        String sql = "SELECT " + VERSION_COLUMNS
                + " FROM skill_versions WHERE skill_row_id IN (SELECT id FROM skills WHERE org_id=:actorOrg) AND content_hash = :contentHash";
        return jdbc.query(sql, params("contentHash", contentHash), versionMapper)
                .stream().findFirst();
    }

    public Optional<SkillVersion> findVersionByContentHash(UUID skillRowId, String contentHash) {
        String sql = "SELECT " + VERSION_COLUMNS + " FROM skill_versions WHERE skill_row_id=:skillRowId AND content_hash=:contentHash AND skill_row_id IN (SELECT id FROM skills WHERE org_id=:actorOrg)";
        return jdbc.query(sql, params("skillRowId",skillRowId,"contentHash",contentHash), versionMapper).stream().findFirst();
    }

    public List<SkillVersion> listVersions(UUID skillRowId) {
        String sql = "SELECT " + VERSION_COLUMNS
                + " FROM skill_versions WHERE skill_row_id IN (SELECT id FROM skills WHERE org_id=:actorOrg) AND skill_row_id = :skillRowId ORDER BY version_no";
        return jdbc.query(sql, params("skillRowId", skillRowId), versionMapper);
    }

    public int nextVersionNo(UUID skillRowId) {
        String sql = "SELECT COALESCE(MAX(version_no), 0) + 1 AS next FROM skill_versions "
                + "WHERE skill_row_id IN (SELECT id FROM skills WHERE org_id=:actorOrg) AND skill_row_id = :skillRowId";
        return jdbc.queryForObject(sql, params("skillRowId", skillRowId), Integer.class);
    }

    // ---- 包文件表 -----------------------------------------------------------

    public void insertPackageFiles(List<SkillPackageFile> files) {
        access.manage();
        for (SkillPackageFile file : files) requireManagedVersion(file.versionId());
        for (SkillPackageFile file : files) {
            String sql = """
                    INSERT INTO skill_package_files
                        (id, version_id, relative_path, kind, size_bytes, sha256, content)
                    VALUES
                        (:id, :versionId, :path, :kind, :sizeBytes, :sha256, :content)
                    """;
            jdbc.update(sql, new MapSqlParameterSource(params())
                    .addValue("id", file.id())
                    .addValue("versionId", file.versionId())
                    .addValue("path", file.relativePath())
                    .addValue("kind", file.kind().code())
                    .addValue("sizeBytes", file.sizeBytes())
                    .addValue("sha256", file.sha256())
                    .addValue("content", file.content()));
        }
    }

    public List<SkillPackageFile> listPackageFiles(UUID versionId) {
        String sql = "SELECT * FROM skill_package_files WHERE version_id IN (SELECT v.id FROM skill_versions v JOIN skills s ON s.id=v.skill_row_id WHERE s.org_id=:actorOrg) AND version_id = :versionId "
                + "ORDER BY relative_path";
        return jdbc.query(sql, params("versionId", versionId), fileMapper);
    }

    public Optional<SkillPackageFile> findPackageFile(UUID versionId, String relativePath) {
        String sql = "SELECT * FROM skill_package_files "
                + "WHERE version_id IN (SELECT v.id FROM skill_versions v JOIN skills s ON s.id=v.skill_row_id WHERE s.org_id=:actorOrg) AND version_id = :versionId AND relative_path = :path";
        return jdbc.query(sql, params("versionId", versionId, "path", relativePath), fileMapper)
                .stream().findFirst();
    }

    // ---- 暂存导入表 ---------------------------------------------------------

    public SkillStagedImport insertStagedImport(SkillStagedImport staged) {
        access.manage();
        String sql = """
                INSERT INTO skill_staged_imports
                    (id, org_id, source_kind, source_identity, manifest, file_entries,
                     total_bytes, file_count, content_hash, status, rejected_reason,
                     created_at, installed_at)
                VALUES
                    (:id, :actorOrg, :sourceKind, :sourceIdentity, :manifest, :fileEntries,
                     :totalBytes, :fileCount, :contentHash, :status, :rejectedReason,
                     :createdAt, :installedAt)
                """;
        jdbc.update(sql, new MapSqlParameterSource(params())
                .addValue("id", staged.id())
                .addValue("sourceKind", staged.sourceKind().code())
                .addValue("sourceIdentity", staged.sourceIdentity())
                .addValue("manifest", staged.manifest())
                .addValue("fileEntries", staged.fileEntries())
                .addValue("totalBytes", staged.totalBytes())
                .addValue("fileCount", staged.fileCount())
                .addValue("contentHash", staged.contentHash())
                .addValue("status", staged.status().name())
                .addValue("rejectedReason", staged.rejectedReason())
                .addValue("createdAt", Timestamp.from(staged.createdAt()))
                .addValue("installedAt", staged.installedAt() == null
                        ? null : Timestamp.from(staged.installedAt())));
        return staged;
    }

    public Optional<SkillStagedImport> findStagedImport(UUID id) {
        String sql = "SELECT * FROM skill_staged_imports WHERE org_id=:actorOrg AND id = :id";
        return jdbc.query(sql, params("id", id), stagedMapper).stream().findFirst();
    }

    public void updateStagedImportStatus(UUID id, SkillStagedImport.Status status,
                                         String rejectedReason) {
        requireManagedStaged(id);
        MapSqlParameterSource params = new MapSqlParameterSource(params())
                .addValue("id", id)
                .addValue("status", status.name())
                .addValue("rejectedReason", rejectedReason);
        if (status == SkillStagedImport.Status.INSTALLED) {
            params.addValue("installedAt", Timestamp.from(Instant.now()));
            jdbc.update("""
                    UPDATE skill_staged_imports
                    SET status = :status, rejected_reason = :rejectedReason,
                        installed_at = :installedAt
                    WHERE org_id=:actorOrg AND id = :id
                    """, params);
        } else {
            jdbc.update("""
                    UPDATE skill_staged_imports
                    SET status = :status, rejected_reason = :rejectedReason
                    WHERE org_id=:actorOrg AND id = :id
                    """, params);
        }
    }

    public List<SkillStagedImport> listStagedImports(List<SkillStagedImport.Status> statuses) {
        String sql = "SELECT * FROM skill_staged_imports WHERE org_id=:actorOrg AND status IN (:statuses) "
                + "ORDER BY created_at DESC";
        return jdbc.query(sql, params("statuses",
                        statuses.stream().map(Enum::name).toList()), stagedMapper);
    }

    public void deleteStagedImport(UUID id) {
        requireManagedStaged(id);
        jdbc.update("DELETE FROM skill_staged_files WHERE staged_import_id IN (SELECT id FROM skill_staged_imports WHERE org_id=:actorOrg) AND staged_import_id = :id", params("id", id));
        jdbc.update("DELETE FROM skill_staged_imports WHERE org_id=:actorOrg AND id = :id", params("id", id));
    }

    public void insertStagedFiles(UUID stagedImportId, List<StagedFile> files) {
        requireManagedStaged(stagedImportId);
        for (StagedFile file : files) {
            String sql = """
                    INSERT INTO skill_staged_files
                        (id, staged_import_id, relative_path, sha256, content)
                    VALUES
                        (:id, :stagedImportId, :path, :sha256, :content)
                    """;
            jdbc.update(sql, new MapSqlParameterSource(params())
                    .addValue("id", UUID.randomUUID())
                    .addValue("stagedImportId", stagedImportId)
                    .addValue("path", file.relativePath())
                    .addValue("sha256", file.sha256())
                    .addValue("content", file.content()));
        }
    }

    public List<StagedFile> listStagedFiles(UUID stagedImportId) {
        String sql = "SELECT relative_path, sha256, content FROM skill_staged_files "
                + "WHERE staged_import_id IN (SELECT id FROM skill_staged_imports WHERE org_id=:actorOrg) AND staged_import_id = :stagedImportId ORDER BY relative_path";
        return jdbc.query(sql, params("stagedImportId", stagedImportId), (rs, rowNum) ->
                new StagedFile(rs.getString("relative_path"), rs.getString("sha256"),
                        rs.getBytes("content")));
    }

    public void deleteStagedFiles(UUID stagedImportId) {
        requireManagedStaged(stagedImportId);
        jdbc.update("DELETE FROM skill_staged_files WHERE staged_import_id IN (SELECT id FROM skill_staged_imports WHERE org_id=:actorOrg) AND staged_import_id = :stagedImportId",
                params("stagedImportId", stagedImportId));
    }

    // ---- 激活记录表 ----------------------------------------------------------

    public void recordActivation(UUID projectId, UUID runId, String skillId, UUID versionId,
                                 String sourceIdentity, String contentHash) {
        access.execution(projectId,runId);
        access.project(projectId, com.specagent.access.Capability.PROJECT_WRITE);
        SkillVersion version = findVersion(versionId).orElseThrow(() -> com.specagent.access.AccessDeniedException.notVisible("Skill version not found"));
        Skill skill = findSkill(skillId).orElseThrow(() -> com.specagent.access.AccessDeniedException.notVisible("Skill not found"));
        if (!version.skillRowId().equals(skill.id())) throw com.specagent.access.AccessDeniedException.notVisible("Skill version not found");
        String sql = """
                INSERT INTO skill_activations
                    (id, org_id, project_id, run_id, verified_run_id, actor_user_id, actor_source, skill_id, version_id, source_identity,
                     content_hash, created_at)
                VALUES
                    (:id, :actorOrg, :projectId, :runId, :runId, :actorUser, :actorSource, :skillId, :versionId, :sourceIdentity,
                     :contentHash, :createdAt)
                ON CONFLICT (verified_run_id,version_id) WHERE verified_run_id IS NOT NULL DO NOTHING
                """;
        jdbc.update(sql, new MapSqlParameterSource(params())
                .addValue("id", UUID.randomUUID())
                .addValue("projectId", projectId)
                .addValue("runId", runId)
                .addValue("skillId", skillId)
                .addValue("versionId", versionId)
                .addValue("sourceIdentity", sourceIdentity)
                .addValue("contentHash", contentHash)
                .addValue("createdAt", Timestamp.from(Instant.now())));
    }

    /** 单个项目最近的激活记录(有界)。 */
    public List<UUID> recentActivatedVersionIds(UUID projectId, int limit) {
        access.project(projectId, com.specagent.access.Capability.PROJECT_READ);
        String sql = """
                SELECT version_id FROM skill_activations
                WHERE org_id=:actorOrg AND project_id = :projectId
                ORDER BY created_at DESC
                LIMIT :limit
                """;
        return jdbc.query(sql, params("projectId", projectId, "limit", limit),
                (rs, rowNum) -> rs.getObject("version_id", UUID.class));
    }

    public void deleteVersionsAndFiles(UUID skillRowId) {
        requireManagedSkill(skillRowId);
        jdbc.update("UPDATE skills SET current_version_id=NULL WHERE id=:id AND org_id=:actorOrg", params("id",skillRowId));
        List<UUID> versionIds = jdbc.query(
                "SELECT id FROM skill_versions WHERE skill_row_id IN (SELECT id FROM skills WHERE org_id=:actorOrg) AND skill_row_id = :skillRowId",
                params("skillRowId", skillRowId), (rs, rowNum) -> rs.getObject("id", UUID.class));
        if (versionIds.isEmpty()) {
            return;
        }
        Map<String, Object> params = params("versionIds", versionIds);
        jdbc.update("DELETE FROM skill_activations WHERE version_id IN (:versionIds)", params);
        jdbc.update("DELETE FROM skill_package_files WHERE version_id IN (:versionIds)", params);
        jdbc.update("DELETE FROM skill_versions WHERE id IN (:versionIds)", params);
    }

    /**
     * 返回已安装版本的文件清单(路径 + 类型 + 大小 + 哈希),不含字节负载,
     * 供有界的目录投影使用。
     */
    public List<FileSummary> listFileSummaries(UUID versionId) {
        String sql = "SELECT relative_path, kind, size_bytes, sha256 "
                + "FROM skill_package_files WHERE version_id IN (SELECT v.id FROM skill_versions v JOIN skills s ON s.id=v.skill_row_id WHERE s.org_id=:actorOrg) AND version_id = :versionId ORDER BY relative_path";
        return jdbc.query(sql, params("versionId", versionId), (rs, rowNum) ->
                new FileSummary(rs.getString("relative_path"),
                        SkillPackageFile.FileKind.fromCode(rs.getString("kind")),
                        rs.getLong("size_bytes"), rs.getString("sha256")));
    }

    public record FileSummary(String relativePath, SkillPackageFile.FileKind kind,
                              long sizeBytes, String sha256) {
    }

    public record StagedFile(String relativePath, String sha256, byte[] content) {
    }

    private Map<String,Object> params(Object... pairs) {
        Map<String,Object> values = new java.util.HashMap<>();
        var actor=access.actor();
        values.put("actorOrg",actor.orgId());
        values.put("actorUser",actor.userId());
        values.put("actorSource",actor.source().name());
        for (int i=0;i<pairs.length;i+=2) values.put((String)pairs[i],pairs[i+1]);
        return values;
    }

    private void requireManagedSkill(UUID id) {
        access.manage();
        if (findSkillById(id).isEmpty()) throw com.specagent.access.AccessDeniedException.notVisible("Skill not found");
    }

    private void requireManagedVersion(UUID id) {
        access.manage();
        if (findVersion(id).isEmpty()) throw com.specagent.access.AccessDeniedException.notVisible("Skill version not found");
    }

    private void requireManagedStaged(UUID id) {
        access.manage();
        if (findStagedImport(id).isEmpty()) throw com.specagent.access.AccessDeniedException.notVisible("Skill import not found");
    }

    private void requireVersionParent(UUID skillId, UUID versionId) {
        if (versionId == null) return;
        SkillVersion version = findVersion(versionId).orElseThrow(() -> com.specagent.access.AccessDeniedException.notVisible("Skill version not found"));
        if (!skillId.equals(version.skillRowId())) throw com.specagent.access.AccessDeniedException.notVisible("Skill version not found");
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}

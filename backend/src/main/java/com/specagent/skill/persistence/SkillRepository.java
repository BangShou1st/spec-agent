package com.specagent.skill.persistence;

import com.specagent.common.Json;
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
 * Durable store for the Skill Runtime. The database is the authoritative
 * Skill state: installed versions are immutable rows with content hashes, and
 * staged imports are separate review records. Nothing lives on the host
 * filesystem.
 *
 * <p>This repository is deliberately a persistence seam for the Skill domain;
 * the Agent/Brain never consumes it directly.
 */
@Repository
public class SkillRepository {

    private static final String SKILL_COLUMNS = """
            id, skill_id, name, description, source_kind, source_identity,
            current_version_id, enabled, created_at, updated_at""";

    private final NamedParameterJdbcTemplate jdbc;
    private final Json json;

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

    public SkillRepository(NamedParameterJdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---- skills ----------------------------------------------------------

    public Skill insertSkill(Skill skill) {
        String sql = """
                INSERT INTO skills
                    (id, skill_id, name, description, source_kind, source_identity,
                     current_version_id, enabled, created_at, updated_at)
                VALUES
                    (:id, :skillId, :name, :description, :sourceKind, :sourceIdentity,
                     :currentVersionId, :enabled, :createdAt, :updatedAt)
                """;
        jdbc.update(sql, new MapSqlParameterSource()
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
        String sql = "SELECT " + SKILL_COLUMNS + " FROM skills WHERE skill_id = :skillId";
        return jdbc.query(sql, Map.of("skillId", skillId), skillMapper).stream().findFirst();
    }

    public Optional<Skill> findSkillById(UUID id) {
        String sql = "SELECT " + SKILL_COLUMNS + " FROM skills WHERE id = :id";
        return jdbc.query(sql, Map.of("id", id), skillMapper).stream().findFirst();
    }

    public List<Skill> listSkills() {
        String sql = "SELECT " + SKILL_COLUMNS + " FROM skills ORDER BY name";
        return jdbc.query(sql, Map.of(), skillMapper);
    }

    public void updateSkillEnabled(UUID id, boolean enabled) {
        String sql = """
                UPDATE skills SET enabled = :enabled, updated_at = :updatedAt WHERE id = :id
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("enabled", enabled)
                .addValue("updatedAt", Timestamp.from(Instant.now())));
    }

    public void updateSkillCurrentVersion(UUID id, UUID versionId, String description) {
        String sql = """
                UPDATE skills
                SET current_version_id = :versionId, description = :description,
                    updated_at = :updatedAt
                WHERE id = :id
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("versionId", versionId)
                .addValue("description", description)
                .addValue("updatedAt", Timestamp.from(Instant.now())));
    }

    public void deleteSkill(UUID id) {
        jdbc.update("DELETE FROM skills WHERE id = :id", Map.of("id", id));
    }

    public Optional<Skill> findEnabledSkillByExactName(String name) {
        String sql = "SELECT " + SKILL_COLUMNS
                + " FROM skills WHERE name = :name AND enabled = true";
        return jdbc.query(sql, Map.of("name", name), skillMapper).stream().findFirst();
    }

    // ---- versions --------------------------------------------------------

    public SkillVersion insertVersion(SkillVersion version) {
        String sql = """
                INSERT INTO skill_versions
                    (id, skill_row_id, version_no, content_hash, manifest, instructions,
                     source_identity, file_count, total_bytes, created_at)
                VALUES
                    (:id, :skillRowId, :versionNo, :contentHash, :manifest, :instructions,
                     :sourceIdentity, :fileCount, :totalBytes, :createdAt)
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", version.id())
                .addValue("skillRowId", version.skillRowId())
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
        String sql = "SELECT " + VERSION_COLUMNS + " FROM skill_versions WHERE id = :id";
        return jdbc.query(sql, Map.of("id", id), versionMapper).stream().findFirst();
    }

    public Optional<SkillVersion> findVersionByContentHash(String contentHash) {
        String sql = "SELECT " + VERSION_COLUMNS
                + " FROM skill_versions WHERE content_hash = :contentHash";
        return jdbc.query(sql, Map.of("contentHash", contentHash), versionMapper)
                .stream().findFirst();
    }

    public List<SkillVersion> listVersions(UUID skillRowId) {
        String sql = "SELECT " + VERSION_COLUMNS
                + " FROM skill_versions WHERE skill_row_id = :skillRowId ORDER BY version_no";
        return jdbc.query(sql, Map.of("skillRowId", skillRowId), versionMapper);
    }

    public int nextVersionNo(UUID skillRowId) {
        String sql = "SELECT COALESCE(MAX(version_no), 0) + 1 AS next FROM skill_versions "
                + "WHERE skill_row_id = :skillRowId";
        return jdbc.queryForObject(sql, Map.of("skillRowId", skillRowId), Integer.class);
    }

    // ---- package files ---------------------------------------------------

    public void insertPackageFiles(List<SkillPackageFile> files) {
        for (SkillPackageFile file : files) {
            String sql = """
                    INSERT INTO skill_package_files
                        (id, version_id, relative_path, kind, size_bytes, sha256, content)
                    VALUES
                        (:id, :versionId, :path, :kind, :sizeBytes, :sha256, :content)
                    """;
            jdbc.update(sql, new MapSqlParameterSource()
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
        String sql = "SELECT * FROM skill_package_files WHERE version_id = :versionId "
                + "ORDER BY relative_path";
        return jdbc.query(sql, Map.of("versionId", versionId), fileMapper);
    }

    public Optional<SkillPackageFile> findPackageFile(UUID versionId, String relativePath) {
        String sql = "SELECT * FROM skill_package_files "
                + "WHERE version_id = :versionId AND relative_path = :path";
        return jdbc.query(sql, Map.of("versionId", versionId, "path", relativePath), fileMapper)
                .stream().findFirst();
    }

    // ---- staged imports --------------------------------------------------

    public SkillStagedImport insertStagedImport(SkillStagedImport staged) {
        String sql = """
                INSERT INTO skill_staged_imports
                    (id, source_kind, source_identity, manifest, file_entries,
                     total_bytes, file_count, content_hash, status, rejected_reason,
                     created_at, installed_at)
                VALUES
                    (:id, :sourceKind, :sourceIdentity, :manifest, :fileEntries,
                     :totalBytes, :fileCount, :contentHash, :status, :rejectedReason,
                     :createdAt, :installedAt)
                """;
        jdbc.update(sql, new MapSqlParameterSource()
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
        String sql = "SELECT * FROM skill_staged_imports WHERE id = :id";
        return jdbc.query(sql, Map.of("id", id), stagedMapper).stream().findFirst();
    }

    public void updateStagedImportStatus(UUID id, SkillStagedImport.Status status,
                                         String rejectedReason) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status.name())
                .addValue("rejectedReason", rejectedReason);
        if (status == SkillStagedImport.Status.INSTALLED) {
            params.addValue("installedAt", Timestamp.from(Instant.now()));
            jdbc.update("""
                    UPDATE skill_staged_imports
                    SET status = :status, rejected_reason = :rejectedReason,
                        installed_at = :installedAt
                    WHERE id = :id
                    """, params);
        } else {
            jdbc.update("""
                    UPDATE skill_staged_imports
                    SET status = :status, rejected_reason = :rejectedReason
                    WHERE id = :id
                    """, params);
        }
    }

    public List<SkillStagedImport> listStagedImports(List<SkillStagedImport.Status> statuses) {
        String sql = "SELECT * FROM skill_staged_imports WHERE status IN (:statuses) "
                + "ORDER BY created_at DESC";
        return jdbc.query(sql, Map.of("statuses",
                        statuses.stream().map(Enum::name).toList()), stagedMapper);
    }

    public void deleteStagedImport(UUID id) {
        jdbc.update("DELETE FROM skill_staged_files WHERE staged_import_id = :id", Map.of("id", id));
        jdbc.update("DELETE FROM skill_staged_imports WHERE id = :id", Map.of("id", id));
    }

    public void insertStagedFiles(UUID stagedImportId, List<StagedFile> files) {
        for (StagedFile file : files) {
            String sql = """
                    INSERT INTO skill_staged_files
                        (id, staged_import_id, relative_path, sha256, content)
                    VALUES
                        (:id, :stagedImportId, :path, :sha256, :content)
                    """;
            jdbc.update(sql, new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("stagedImportId", stagedImportId)
                    .addValue("path", file.relativePath())
                    .addValue("sha256", file.sha256())
                    .addValue("content", file.content()));
        }
    }

    public List<StagedFile> listStagedFiles(UUID stagedImportId) {
        String sql = "SELECT relative_path, sha256, content FROM skill_staged_files "
                + "WHERE staged_import_id = :stagedImportId ORDER BY relative_path";
        return jdbc.query(sql, Map.of("stagedImportId", stagedImportId), (rs, rowNum) ->
                new StagedFile(rs.getString("relative_path"), rs.getString("sha256"),
                        rs.getBytes("content")));
    }

    public void deleteStagedFiles(UUID stagedImportId) {
        jdbc.update("DELETE FROM skill_staged_files WHERE staged_import_id = :stagedImportId",
                Map.of("stagedImportId", stagedImportId));
    }

    // ---- activations -----------------------------------------------------

    public void recordActivation(UUID projectId, UUID runId, String skillId, UUID versionId,
                                 String sourceIdentity, String contentHash) {
        String sql = """
                INSERT INTO skill_activations
                    (id, project_id, run_id, skill_id, version_id, source_identity,
                     content_hash, created_at)
                VALUES
                    (:id, :projectId, :runId, :skillId, :versionId, :sourceIdentity,
                     :contentHash, :createdAt)
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("projectId", projectId)
                .addValue("runId", runId)
                .addValue("skillId", skillId)
                .addValue("versionId", versionId)
                .addValue("sourceIdentity", sourceIdentity)
                .addValue("contentHash", contentHash)
                .addValue("createdAt", Timestamp.from(Instant.now())));
    }

    /** Newest activations for one project (bounded). */
    public List<UUID> recentActivatedVersionIds(UUID projectId, int limit) {
        String sql = """
                SELECT version_id FROM skill_activations
                WHERE project_id = :projectId
                ORDER BY created_at DESC
                LIMIT :limit
                """;
        return jdbc.query(sql, Map.of("projectId", projectId, "limit", limit),
                (rs, rowNum) -> rs.getObject("version_id", UUID.class));
    }

    public void deleteVersionsAndFiles(UUID skillRowId) {
        List<UUID> versionIds = jdbc.query(
                "SELECT id FROM skill_versions WHERE skill_row_id = :skillRowId",
                Map.of("skillRowId", skillRowId), (rs, rowNum) -> rs.getObject("id", UUID.class));
        if (versionIds.isEmpty()) {
            return;
        }
        Map<String, Object> params = Map.of("versionIds", versionIds);
        jdbc.update("DELETE FROM skill_activations WHERE version_id IN (:versionIds)", params);
        jdbc.update("DELETE FROM skill_package_files WHERE version_id IN (:versionIds)", params);
        jdbc.update("DELETE FROM skill_versions WHERE id IN (:versionIds)", params);
    }

    /**
     * Returns the installed version's file list (path + kind + size + hash)
     * without the byte payload for bounded catalog projection.
     */
    public List<FileSummary> listFileSummaries(UUID versionId) {
        String sql = "SELECT relative_path, kind, size_bytes, sha256 "
                + "FROM skill_package_files WHERE version_id = :versionId ORDER BY relative_path";
        return jdbc.query(sql, Map.of("versionId", versionId), (rs, rowNum) ->
                new FileSummary(rs.getString("relative_path"),
                        SkillPackageFile.FileKind.fromCode(rs.getString("kind")),
                        rs.getLong("size_bytes"), rs.getString("sha256")));
    }

    public record FileSummary(String relativePath, SkillPackageFile.FileKind kind,
                              long sizeBytes, String sha256) {
    }

    public record StagedFile(String relativePath, String sha256, byte[] content) {
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
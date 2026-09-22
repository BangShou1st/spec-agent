package com.specagent.retrieval.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.specagent.common.Hashes;
import com.specagent.common.Json;
import com.specagent.common.Maps;
import com.specagent.retrieval.api.RetrievalScope;
import com.specagent.retrieval.api.RetrievalSourceKind;
import com.specagent.retrieval.api.MemoryAuthority;
import com.specagent.retrieval.embedding.EmbeddingGateway;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;

@Repository
public class RetrievalEntryRepository {

    private static final TypeReference<Map<String, Object>> METADATA = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Json json;
    private final RowMapper<RetrievalEntry> rowMapper;

    public RetrievalEntryRepository(NamedParameterJdbcTemplate jdbcTemplate, Json json) {
        this.jdbcTemplate = jdbcTemplate;
        this.json = json;
        this.rowMapper = (rs, rowNum) -> new RetrievalEntry(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("route_id", UUID.class),
                RetrievalSourceKind.valueOf(rs.getString("source_kind")),
                rs.getObject("source_id", UUID.class),
                rs.getString("source_ref"),
                RetrievalScope.valueOf(rs.getString("scope")),
                MemoryAuthority.valueOf(rs.getString("authority")),
                rs.getString("content"),
                rs.getString("content_hash"),
                json.read(rs.getString("metadata"), METADATA),
                rs.getString("embedding_model"),
                (Integer) rs.getObject("embedding_dimensions"),
                rs.getString("embedding_status"),
                rs.getTimestamp("retracted_at") == null
                        ? null : rs.getTimestamp("retracted_at").toInstant());
    }

    public void upsert(RetrievalEntry entry) {
        String sql = """
                INSERT INTO retrieval_entries
                    (id, project_id, route_id, source_kind, source_id, source_ref,
                     scope, authority, content, content_hash, metadata,
                     embedding_model, embedding_dimensions, embedding_status,
                     created_at, updated_at, retracted_at)
                VALUES (:id, :projectId, :routeId, :sourceKind, :sourceId, :sourceRef,
                        :scope, :authority, :content, :contentHash, CAST(:metadata AS jsonb),
                        :embeddingModel, :embeddingDimensions,
                        CASE WHEN CAST(:retractedAt AS timestamp) IS NULL THEN :embeddingStatus ELSE 'UNAVAILABLE' END,
                        NOW(), NOW(), :retractedAt)
                ON CONFLICT (project_id, source_ref) DO UPDATE SET
                    route_id = EXCLUDED.route_id,
                    source_kind = EXCLUDED.source_kind,
                    source_id = EXCLUDED.source_id,
                    scope = EXCLUDED.scope,
                    authority = EXCLUDED.authority,
                    content = EXCLUDED.content,
                    content_hash = EXCLUDED.content_hash,
                    metadata = EXCLUDED.metadata,
                    embedding = CASE
                        WHEN retrieval_entries.content_hash <> EXCLUDED.content_hash
                        THEN NULL ELSE retrieval_entries.embedding END,
                    embedding_model = CASE
                        WHEN retrieval_entries.content_hash <> EXCLUDED.content_hash
                        THEN NULL ELSE retrieval_entries.embedding_model END,
                    embedding_dimensions = CASE
                        WHEN retrieval_entries.content_hash <> EXCLUDED.content_hash
                        THEN NULL ELSE retrieval_entries.embedding_dimensions END,
                    embedding_status = CASE
                        WHEN retrieval_entries.content_hash <> EXCLUDED.content_hash
                        THEN CASE WHEN EXCLUDED.retracted_at IS NULL THEN 'PENDING' ELSE 'UNAVAILABLE' END
                        ELSE EXCLUDED.embedding_status END,
                    updated_at = NOW(),
                    retracted_at = EXCLUDED.retracted_at
                """;
        jdbcTemplate.update(sql, Maps.of(
                "id", entry.id(),
                "projectId", entry.projectId(),
                "routeId", entry.routeId(),
                "sourceKind", entry.sourceKind().name(),
                "sourceId", entry.sourceId(),
                "sourceRef", entry.sourceRef(),
                "scope", entry.scope().name(),
                "authority", entry.authority().name(),
                "content", entry.content(),
                "contentHash", entry.contentHash(),
                "metadata", json.write(entry.metadata()),
                "embeddingModel", entry.embeddingModel(),
                "embeddingDimensions", entry.embeddingDimensions(),
                "embeddingStatus", entry.embeddingStatus(),
                "retractedAt", entry.retractedAt() == null ? null : Timestamp.from(entry.retractedAt())));
    }

    public void deleteProject(UUID projectId) {
        jdbcTemplate.update("DELETE FROM retrieval_entries WHERE project_id = :projectId",
                Map.of("projectId", projectId));
    }

    public void deleteSource(UUID projectId, String sourceRef) {
        jdbcTemplate.update("""
                DELETE FROM retrieval_entries
                WHERE project_id = :projectId AND source_ref = :sourceRef
                """, Maps.of("projectId", projectId, "sourceRef", sourceRef));
    }

    public void deleteSourcePrefix(UUID projectId, String sourceRefPrefix) {
        jdbcTemplate.update("""
                DELETE FROM retrieval_entries
                WHERE project_id = :projectId AND source_ref LIKE :sourceRefPrefix
                """, Maps.of("projectId", projectId,
                "sourceRefPrefix", sourceRefPrefix + "%"));
    }

    /**
     * Updates only derived route provenance. Content and embedding columns are
     * deliberately untouched so a metadata-only route membership change does
     * not discard a valid READY embedding.
     */
    public void updateRouteProvenance(UUID projectId,
                                      String sourceRef,
                                      UUID routeId,
                                      List<String> originRouteIds,
                                      boolean workspaceScoped) {
        updateRouteProvenanceWhere(projectId, routeId, originRouteIds, workspaceScoped,
                "source_ref = :sourceRef", Map.of("sourceRef", sourceRef));
    }

    /** Updates route provenance for all chunks belonging to one resource. */
    public void updateRouteProvenancePrefix(UUID projectId,
                                            String sourceRefPrefix,
                                            UUID routeId,
                                            List<String> originRouteIds,
                                            boolean workspaceScoped) {
        updateRouteProvenanceWhere(projectId, routeId, originRouteIds, workspaceScoped,
                "source_ref LIKE :sourceRefPrefix",
                Map.of("sourceRefPrefix", sourceRefPrefix + "%"));
    }

    private void updateRouteProvenanceWhere(UUID projectId,
                                            UUID routeId,
                                            List<String> originRouteIds,
                                            boolean workspaceScoped,
                                            String predicate,
                                            Map<String, Object> predicateParams) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("projectId", projectId);
        params.put("routeId", routeId);
        params.put("metadataPatch", json.write(Map.of(
                "originRouteIds", originRouteIds,
                "workspaceScoped", workspaceScoped)));
        params.putAll(predicateParams);
        jdbcTemplate.update("""
                UPDATE retrieval_entries
                SET route_id = :routeId,
                    metadata = metadata || CAST(:metadataPatch AS jsonb),
                    updated_at = NOW()
                WHERE project_id = :projectId AND %s
                """.formatted(predicate), params);
    }

    /** Retraction keeps the derived audit row but removes it from retrieval. */
    public void retractSource(UUID projectId, String sourceRef) {
        jdbcTemplate.update("""
                UPDATE retrieval_entries
                SET retracted_at = COALESCE(retracted_at, NOW()),
                    embedding = NULL,
                    embedding_model = NULL,
                    embedding_dimensions = NULL,
                    embedding_status = 'UNAVAILABLE',
                    updated_at = NOW()
                WHERE project_id = :projectId AND source_ref = :sourceRef
                """, Maps.of("projectId", projectId, "sourceRef", sourceRef));
    }

    public void retractSourcePrefix(UUID projectId, String sourceRefPrefix) {
        jdbcTemplate.update("""
                UPDATE retrieval_entries
                SET retracted_at = COALESCE(retracted_at, NOW()),
                    embedding = NULL,
                    embedding_model = NULL,
                    embedding_dimensions = NULL,
                    embedding_status = 'UNAVAILABLE',
                    updated_at = NOW()
                WHERE project_id = :projectId AND source_ref LIKE :sourceRefPrefix
                """, Maps.of("projectId", projectId,
                "sourceRefPrefix", sourceRefPrefix + "%"));
    }

    public List<RetrievalEntry> findPending(UUID projectId, int limit) {
        String sql = """
                SELECT * FROM retrieval_entries
                WHERE project_id = :projectId AND retracted_at IS NULL
                  AND embedding_status = 'PENDING'
                ORDER BY updated_at, source_ref
                LIMIT :limit
                """;
        return jdbcTemplate.query(sql, Maps.of("projectId", projectId,
                "limit", Math.max(1, Math.min(limit, 512))), rowMapper);
    }

    /** Project ids with pending derived work for the background enrichment worker. */
    public List<UUID> findPendingProjectIds(int limit) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT project_id FROM retrieval_entries
                WHERE retracted_at IS NULL AND embedding_status = 'PENDING'
                ORDER BY project_id
                LIMIT :limit
                """, Map.of("limit", Math.max(1, Math.min(limit, 512))), UUID.class);
    }

    public Optional<RetrievalEntry> findBySourceRef(UUID projectId, String sourceRef) {
        return jdbcTemplate.query("""
                SELECT * FROM retrieval_entries
                WHERE project_id = :projectId AND source_ref = :sourceRef
                """, Maps.of("projectId", projectId, "sourceRef", sourceRef), rowMapper)
                .stream().findFirst();
    }

    public List<RetrievalEntry> findByProject(UUID projectId) {
        return jdbcTemplate.query("""
                SELECT * FROM retrieval_entries
                WHERE project_id = :projectId
                ORDER BY source_ref
                """, Maps.of("projectId", projectId), rowMapper);
    }

    public void markEmbeddingReady(UUID id, String contentHash,
                                   EmbeddingGateway.Embedding embedding) {
        jdbcTemplate.update("""
                UPDATE retrieval_entries
                SET embedding = CAST(:embedding AS vector),
                    embedding_model = :embeddingModel,
                    embedding_dimensions = :embeddingDimensions,
                    embedding_status = 'READY',
                    updated_at = NOW()
                WHERE id = :id AND content_hash = :contentHash
                  AND retracted_at IS NULL
                """, Maps.of("id", id, "contentHash", contentHash,
                "embedding", vectorLiteral(embedding.values()),
                "embeddingModel", embedding.model(),
                "embeddingDimensions", embedding.dimensions()));
    }

    public void markEmbeddingStatus(UUID id, String contentHash, String status) {
        jdbcTemplate.update("""
                UPDATE retrieval_entries
                SET embedding = NULL,
                    embedding_model = NULL,
                    embedding_dimensions = NULL,
                    embedding_status = :status,
                    updated_at = NOW()
                WHERE id = :id AND content_hash = :contentHash
                  AND retracted_at IS NULL
                """, Maps.of("id", id, "contentHash", contentHash, "status", status));
    }

    public List<RetrievalEntry> lexical(UUID projectId, String queryText, int limit,
                                        String sourceKind, List<String> sourceRefs) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM retrieval_entries
                WHERE project_id = :projectId AND retracted_at IS NULL
                  AND search_vector @@ plainto_tsquery('simple', :queryText)
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("projectId", projectId);
        params.put("queryText", queryText);
        appendFilters(sql, params, sourceKind, sourceRefs);
        sql.append(" ORDER BY ts_rank_cd(search_vector, plainto_tsquery('simple', :queryText)) DESC, updated_at DESC LIMIT :limit");
        params.put("limit", Math.max(1, limit));
        return jdbcTemplate.query(sql.toString(), params, rowMapper);
    }

    public List<RetrievalEntry> trigram(UUID projectId, String queryText, int limit,
                                        String sourceKind, List<String> sourceRefs) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM retrieval_entries
                WHERE project_id = :projectId AND retracted_at IS NULL
                  AND (similarity(content, :queryText) > 0.15
                       OR word_similarity(:queryText, content) > 0.15)
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("projectId", projectId);
        params.put("queryText", queryText);
        appendFilters(sql, params, sourceKind, sourceRefs);
        sql.append(" ORDER BY GREATEST(similarity(content, :queryText), "
                + "word_similarity(:queryText, content)) DESC, updated_at DESC LIMIT :limit");
        params.put("limit", Math.max(1, limit));
        return jdbcTemplate.query(sql.toString(), params, rowMapper);
    }

    public List<RetrievalEntry> findBySourceRefs(UUID projectId, List<String> sourceRefs) {
        if (sourceRefs == null || sourceRefs.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                SELECT * FROM retrieval_entries
                WHERE project_id = :projectId AND retracted_at IS NULL
                  AND source_ref IN (:sourceRefs)
                """, Maps.of("projectId", projectId, "sourceRefs", sourceRefs), rowMapper);
    }

    public List<String> sourceRefsForRoute(UUID projectId, UUID routeId) {
        return jdbcTemplate.queryForList("""
                SELECT source_ref FROM retrieval_entries
                WHERE project_id = :projectId AND route_id = :routeId AND retracted_at IS NULL
                ORDER BY created_at, source_ref
                """, Maps.of("projectId", projectId, "routeId", routeId), String.class);
    }

    public Optional<List<RetrievalEntry>> vector(UUID projectId,
                                                 EmbeddingGateway.Embedding embedding,
                                                 int limit,
                                                 List<String> sourceRefs) {
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM retrieval_entries
                WHERE project_id = :projectId AND retracted_at IS NULL
                  AND embedding IS NOT NULL
                  AND embedding_status = 'READY'
                  AND embedding_model = :embeddingModel
                  AND embedding_dimensions = :embeddingDimensions
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("projectId", projectId);
        params.put("embeddingModel", embedding.model());
        params.put("embeddingDimensions", embedding.dimensions());
        params.put("embedding", vectorLiteral(embedding.values()));
        if (sourceRefs != null && !sourceRefs.isEmpty()) {
            sql.append(" AND source_ref IN (:sourceRefs)");
            params.put("sourceRefs", sourceRefs);
        }
        sql.append(" ORDER BY embedding <=> CAST(:embedding AS vector) LIMIT :limit");
        params.put("limit", Math.max(1, limit));
        return Optional.of(jdbcTemplate.query(sql.toString(), params, rowMapper));
    }

    private String vectorLiteral(float[] values) {
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(values[i]);
        }
        return literal.append(']').toString();
    }

    private void appendFilters(StringBuilder sql, Map<String, Object> params,
                               String sourceKind, List<String> sourceRefs) {
        if (sourceKind != null && !sourceKind.isBlank()) {
            sql.append(" AND source_kind = :sourceKind");
            params.put("sourceKind", sourceKind);
        }
        if (sourceRefs != null && !sourceRefs.isEmpty()) {
            sql.append(" AND source_ref IN (:sourceRefs)");
            params.put("sourceRefs", sourceRefs);
        }
    }
}

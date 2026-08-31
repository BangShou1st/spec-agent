package com.specagent.graph;

import com.specagent.node.Node;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Blocker 6: symmetric relation migration / DB uniqueness backstop.
 *
 * <p>These tests exercise the contract introduced by V18: ACTIVE
 * {@code RELATED_TO} / {@code CONFLICTS_WITH} relations are a single unordered
 * fact per node pair, enforced by the partial unique index
 * {@code idx_node_relations_symmetric_active_unique} (identity
 * {@code (project_id, relation_type, LEAST, GREATEST)}). Directional types keep
 * their authored-direction uniqueness. The repository also canonicalizes
 * symmetric endpoints at write time, mirroring the index identity.
 *
 * <p>The suite's test database has V18 applied at context start, so the unique
 * index is already present; the tests probe both the application-level
 * canonicalization and the raw database backstop.
 */
@SpringBootTest
@ActiveProfiles("test")
class NodeRelationSymmetricUniquenessIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService commandService;
    @Autowired private RouteRepository routeRepository;
    @Autowired private NodeRelationRepository relationRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;

    private Project project;
    private Node nodeA;
    private Node nodeB;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("对称关系唯一性 " + UUID.randomUUID());
        Route route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        nodeA = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "A"));
        nodeB = commandService.appendContinuation(
                project.id(), route.id(), nodeA.id(), "NOTE", Map.of("text", "B")).node();
    }

    @AfterEach
    void cleanUp() {
        if (project == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM graph_operations WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM node_relations WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM nodes WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM routes WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM projects WHERE id = ?", project.id());
    }

    /** Raw insert of the reverse direction must be blocked by the unique index. */
    @Test
    void reverseRelatedToDuplicateIsRejectedByUniqueIndex() {
        // First row is written canonically (as the application always does); a
        // raw reverse-direction insert must then be rejected by the index.
        relationRepository.insertActiveOrThrowDuplicate(
                project.id(), nodeA.id(), nodeB.id(), NodeRelationType.RELATED_TO,
                NodeRelation.Origin.USER, null, null);

        assertThatThrownBy(() ->
                relationRepository.save(makeRelation(nodeB.id(), nodeA.id(), NodeRelationType.RELATED_TO)))
                .isInstanceOf(DuplicateKeyException.class);

        // Exactly one ACTIVE row survives, in canonical (min, max) order.
        List<NodeRelation> active = relationRepository.findActiveByProject(project.id());
        assertThat(active).hasSize(1);
        assertThat(active.get(0).sourceNodeId())
                .isEqualTo(min(nodeA.id(), nodeB.id()));
        assertThat(active.get(0).targetNodeId())
                .isEqualTo(max(nodeA.id(), nodeB.id()));
    }

    @Test
    void reverseConflictsWithDuplicateIsRejectedByUniqueIndex() {
        relationRepository.save(makeRelation(nodeA.id(), nodeB.id(), NodeRelationType.CONFLICTS_WITH));

        assertThatThrownBy(() ->
                relationRepository.save(makeRelation(nodeB.id(), nodeA.id(), NodeRelationType.CONFLICTS_WITH)))
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(relationRepository.findActiveByProject(project.id())).hasSize(1);
    }

    /**
     * Directional types are NOT deduplicated by the symmetric index: A DEPENDS_ON
     * B and B DEPENDS_ON A stay distinct, preserving the original directed
     * uniqueness contract.
     */
    @Test
    void directionalRelationKeepsAuthoredDirectionUniqueness() {
        relationRepository.save(makeRelation(nodeA.id(), nodeB.id(), NodeRelationType.DEPENDS_ON));
        relationRepository.save(makeRelation(nodeB.id(), nodeA.id(), NodeRelationType.DEPENDS_ON));

        List<NodeRelation> active = relationRepository.findActiveByProject(project.id());
        assertThat(active).hasSize(2);
        assertThat(active).allMatch(r -> r.relationType() == NodeRelationType.DEPENDS_ON);
    }

    /** The canonical pair lookup ignores endpoint order for symmetric types. */
    @Test
    void canonicalPairLookupIgnoresEndpointOrder() {
        relationRepository.insertActiveOrThrowDuplicate(
                project.id(), nodeA.id(), nodeB.id(), NodeRelationType.RELATED_TO,
                NodeRelation.Origin.USER, null, null);

        assertThat(relationRepository.findActiveByCanonicalPair(
                project.id(), nodeA.id(), nodeB.id(), NodeRelationType.RELATED_TO)).isPresent();
        // Order reversed — still the same canonical fact.
        assertThat(relationRepository.findActiveByCanonicalPair(
                project.id(), nodeB.id(), nodeA.id(), NodeRelationType.RELATED_TO)).isPresent();
        // A different type must not match.
        assertThat(relationRepository.findActiveByCanonicalPair(
                project.id(), nodeA.id(), nodeB.id(), NodeRelationType.CONFLICTS_WITH)).isEmpty();
    }

    /**
     * The repository canonicalizes symmetric endpoints itself, so a reverse
     * authored direction is rejected as a controlled conflict (not a raw 500),
     * and the persisted row is in canonical order.
     */
    @Test
    void insertActiveOrThrowDuplicateCanonicalizesAndRejectsReverse() {
        NodeRelation first = relationRepository.insertActiveOrThrowDuplicate(
                project.id(), nodeA.id(), nodeB.id(), NodeRelationType.RELATED_TO,
                NodeRelation.Origin.USER, null, null);
        assertThat(first.sourceNodeId()).isEqualTo(min(nodeA.id(), nodeB.id()));
        assertThat(first.targetNodeId()).isEqualTo(max(nodeA.id(), nodeB.id()));

        assertThatThrownBy(() -> relationRepository.insertActiveOrThrowDuplicate(
                project.id(), nodeB.id(), nodeA.id(), NodeRelationType.RELATED_TO,
                NodeRelation.Origin.USER, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists")
                .isNotInstanceOf(DuplicateKeyException.class);

        assertThat(relationRepository.findActiveByProject(project.id())).hasSize(1);
    }

    /**
     * V18 Step 2: a single historical ACTIVE symmetric row stored in reverse
     * order is canonicalized to (min, max) by the migration's UPDATE. Validated
     * here by running the same UPDATE against a seeded reverse row within a
     * rolled-back transaction.
     */
    @Test
    void migrationCanonicalizesReverseSingleActiveRow() throws Exception {
        // The migration's canonical direction is the database's own uuid
        // ordering (LEAST/GREATEST), which can differ from Java's
        // UUID.compareTo for some random ids. Compute the DB-canonical pair in
        // SQL and seed the row in the REVERSE direction so the swap is always
        // observable, independent of the node ids.
        Map<String, Object> pair = jdbcTemplate.queryForMap(
                "SELECT LEAST(CAST(? AS uuid), CAST(? AS uuid)) AS lo, "
                        + "GREATEST(CAST(? AS uuid), CAST(? AS uuid)) AS hi",
                nodeA.id(), nodeB.id(), nodeA.id(), nodeB.id());
        UUID lo = (UUID) pair.get("lo");
        UUID hi = (UUID) pair.get("hi");
        UUID source = hi;
        UUID target = lo;
        jdbcTemplate.update(
                "INSERT INTO node_relations (id, project_id, source_node_id, target_node_id, "
                        + "relation_type, origin, status, created_at) VALUES (?, ?, ?, ?, 'RELATED_TO', 'USER', 'ACTIVE', NOW())",
                UUID.randomUUID(), project.id(), source, target);

        // Exactly the migration's Step-2 transformation, scoped to this test's
        // project only so the commit cannot touch other tests' rows. The
        // transformation (swap reverse ACTIVE symmetric rows to min/max) is
        // identical to V18's unscoped UPDATE.
        jdbcTemplate.update(
                "UPDATE node_relations "
                        + "SET source_node_id = LEAST(source_node_id, target_node_id), "
                        + "    target_node_id = GREATEST(source_node_id, target_node_id) "
                        + "WHERE project_id = ? "
                        + "  AND status = 'ACTIVE' "
                        + "  AND relation_type IN ('RELATED_TO', 'CONFLICTS_WITH') "
                        + "  AND source_node_id > target_node_id",
                project.id());

        NodeRelation canonical = relationRepository.findActiveByCanonicalPair(
                project.id(), nodeA.id(), nodeB.id(), NodeRelationType.RELATED_TO).orElseThrow();
        assertThat(canonical.sourceNodeId()).isEqualTo(lo);
        assertThat(canonical.targetNodeId()).isEqualTo(hi);
    }

    /**
     * V18 Step 1 (preflight) fails closed when an unordered pair already carries
     * more than one ACTIVE symmetric relation. Validated by dropping the
     * symmetric index, seeding two reversed ACTIVE rows, then running the
     * migration's preflight guard and asserting it raises with an actionable
     * error. Everything is rolled back so the shared test DB is untouched.
     */
    @Test
    void preflightFailsWhenDuplicateActiveSymmetricRelationExists() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                // Remove the backstop so the conflicting pair can be seeded.
                st.execute("DROP INDEX IF EXISTS idx_node_relations_symmetric_active_unique");

                // Two ACTIVE RELATED_TO rows on the same unordered node pair,
                // stored in opposite directions. Uses this test's real project
                // and nodes (FK-safe); the preflight groups by project + pair.
                UUID a = nodeA.id();
                UUID b = nodeB.id();
                st.execute("INSERT INTO node_relations (id, project_id, source_node_id, target_node_id, "
                        + "relation_type, origin, status, created_at) VALUES ('"
                        + UUID.randomUUID() + "','" + project.id() + "','" + a + "','" + b
                        + "','RELATED_TO','USER','ACTIVE',NOW())");
                st.execute("INSERT INTO node_relations (id, project_id, source_node_id, target_node_id, "
                        + "relation_type, origin, status, created_at) VALUES ('"
                        + UUID.randomUUID() + "','" + project.id() + "','" + b + "','" + a
                        + "','RELATED_TO','USER','ACTIVE',NOW())");

                boolean raised = false;
                try {
                    st.execute("DO $$\n"
                            + "DECLARE r RECORD;\n"
                            + "BEGIN\n"
                            + "  FOR r IN\n"
                            + "    SELECT project_id, relation_type,\n"
                            + "           LEAST(source_node_id, target_node_id) AS lo,\n"
                            + "           GREATEST(source_node_id, target_node_id) AS hi,\n"
                            + "           COUNT(*) AS cnt\n"
                            + "    FROM node_relations\n"
                            + "    WHERE status = 'ACTIVE'\n"
                            + "      AND relation_type IN ('RELATED_TO', 'CONFLICTS_WITH')\n"
                            + "    GROUP BY project_id, relation_type,\n"
                            + "             LEAST(source_node_id, target_node_id),\n"
                            + "             GREATEST(source_node_id, target_node_id)\n"
                            + "    HAVING COUNT(*) > 1\n"
                            + "  LOOP\n"
                            + "    RAISE EXCEPTION 'SYMMETRIC_RELATION_CONFLICT: project % has % ACTIVE % relations on the same unordered node pair (% , %).',\n"
                            + "      r.project_id, r.cnt, r.relation_type, r.lo, r.hi;\n"
                            + "  END LOOP;\n"
                            + "END $$;");
                } catch (SQLException ex) {
                    raised = true;
                    assertThat(ex.getMessage()).contains("SYMMETRIC_RELATION_CONFLICT");
                }
                assertThat(raised)
                        .as("preflight must raise when duplicate active symmetric relations exist")
                        .isTrue();
            } finally {
                conn.rollback();
            }
        }
    }

    private NodeRelation makeRelation(UUID source, UUID target, NodeRelationType type) {
        return new NodeRelation(
                UUID.randomUUID(), project.id(), source, target, type,
                NodeRelation.Origin.USER, NodeRelation.Status.ACTIVE,
                null, null, Instant.now(), null);
    }

    /**
     * Regression: PostgreSQL's uuid ordering (byte-wise RFC 4122, unsigned) is
     * NOT guaranteed to match Java's {@code UUID.compareTo} (two signed 64-bit
     * halves). A row canonicalized by the V18 migration stores the pair in the
     * database's own order; the application duplicate pre-check must therefore
     * use the order-independent canonical-pair lookup, or it would miss the
     * migrated row and surface a raw {@link DuplicateKeyException} from the
     * unique index instead of the controlled "already exists" conflict.
     */
    @Test
    void javaAndPostgresOrderingDivergenceIsRejectedAsControlledConflict() {
        // Deliberately divergent pair: the high bit of A's first 64-bit half is
        // set, so Java sees A < B (signed) while PostgreSQL compares A's first
        // byte 0x80 > B's first byte 0x00, so the DB sees A > B.
        UUID a = UUID.fromString("80000000-0000-0000-0000-000000000000");
        UUID b = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertThat(a.compareTo(b))
                .as("sanity: Java orders A < B for the chosen pair")
                .isNegative();
        Map<String, Object> pair = jdbcTemplate.queryForMap(
                "SELECT LEAST(CAST(? AS uuid), CAST(? AS uuid)) AS lo, "
                        + "GREATEST(CAST(? AS uuid), CAST(? AS uuid)) AS hi",
                a, b, a, b);
        assertThat(pair.get("lo"))
                .as("sanity: PostgreSQL orders B < A for the chosen pair")
                .isEqualTo(b);
        assertThat(pair.get("hi")).isEqualTo(a);

        // Seed the pair as the migrated row already canonicalized to the
        // database's own order (source = DB LEAST, target = DB GREATEST) —
        // exactly what V18 Step 2 leaves behind for such a divergent pair.
        seedNodeRow(a, "KNOWLEDGE");
        seedNodeRow(b, "KNOWLEDGE");
        jdbcTemplate.update(
                "INSERT INTO node_relations (id, project_id, source_node_id, target_node_id, "
                        + "relation_type, origin, status, created_at) "
                        + "VALUES (?, ?, ?, ?, 'RELATED_TO', 'USER', 'ACTIVE', NOW())",
                UUID.randomUUID(), project.id(), b, a);

        // Normal application creation in EITHER direction must be a controlled
        // domain conflict, never a raw DuplicateKeyException.
        assertThatThrownBy(() -> relationRepository.insertActiveOrThrowDuplicate(
                project.id(), a, b, NodeRelationType.RELATED_TO,
                NodeRelation.Origin.USER, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists")
                .isNotInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> relationRepository.insertActiveOrThrowDuplicate(
                project.id(), b, a, NodeRelationType.RELATED_TO,
                NodeRelation.Origin.USER, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists")
                .isNotInstanceOf(DuplicateKeyException.class);

        // Exactly one ACTIVE relation remains, unchanged in the DB-canonical
        // direction the migration established.
        List<NodeRelation> active = relationRepository.findActiveByProject(project.id());
        assertThat(active).hasSize(1);
        assertThat(active.get(0).sourceNodeId()).isEqualTo(b);
        assertThat(active.get(0).targetNodeId()).isEqualTo(a);
    }

    private void seedNodeRow(UUID id, String subtype) {
        jdbcTemplate.update(
                "INSERT INTO nodes (id, project_id, question, kind, subtype, author_kind, "
                        + "knowledge_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'KNOWLEDGE', ?, 'USER', 'PROPOSED', NOW(), NOW())",
                id, project.id(), "seed-" + id, subtype);
    }

    private static UUID min(UUID a, UUID b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static UUID max(UUID a, UUID b) {
        return a.compareTo(b) <= 0 ? b : a;
    }
}

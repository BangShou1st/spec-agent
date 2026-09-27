package com.specagent.workspace.graph;

import com.specagent.workspace.node.Node;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteRepository;
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
 * 文件名:NodeRelationSymmetricUniquenessIntegrationTest.java
 *
 * 测试目标:Blocker 6——对称关系的迁移与数据库唯一性兜底。
 *
 * V18 引入的契约:ACTIVE 状态的 {@code RELATED_TO} / {@code CONFLICTS_WITH}
 * 关系在同一个节点对上是无序的单一事实,由部分唯一索引
 * {@code idx_node_relations_symmetric_active_unique}(身份为
 * {@code (project_id, relation_type, LEAST, GREATEST)})强制保证;方向性类型
 * 保留按编写方向去重的语义。仓储层在写入时也会把对称关系的端点规范化,
 * 与索引身份保持一致。
 *
 * 测试数据库在上下文启动时已应用 V18,唯一索引已存在;用例同时探测
 * 应用层的规范化逻辑和原始数据库层的兜底约束。
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

    /** 反方向的原始插入必须被唯一索引拦截。 */
    @Test
    void reverseRelatedToDuplicateIsRejectedByUniqueIndex() {
        // 第一行按应用层的规范方向写入;随后反方向的原始插入必须被索引拒绝。
        relationRepository.insertActiveOrThrowDuplicate(
                project.id(), nodeA.id(), nodeB.id(), NodeRelationType.RELATED_TO,
                NodeRelation.Origin.USER, null, null);

        assertThatThrownBy(() ->
                relationRepository.save(makeRelation(nodeB.id(), nodeA.id(), NodeRelationType.RELATED_TO)))
                .isInstanceOf(DuplicateKeyException.class);

        // 恰好保留一条 ACTIVE 记录,且处于规范的 (min, max) 顺序。
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
     * 方向性类型不参与对称索引的去重:A DEPENDS_ON B 与 B DEPENDS_ON A
     * 各自独立存在,保留原有的有向唯一性契约。
     */
    @Test
    void directionalRelationKeepsAuthoredDirectionUniqueness() {
        relationRepository.save(makeRelation(nodeA.id(), nodeB.id(), NodeRelationType.DEPENDS_ON));
        relationRepository.save(makeRelation(nodeB.id(), nodeA.id(), NodeRelationType.DEPENDS_ON));

        List<NodeRelation> active = relationRepository.findActiveByProject(project.id());
        assertThat(active).hasSize(2);
        assertThat(active).allMatch(r -> r.relationType() == NodeRelationType.DEPENDS_ON);
    }

    /** 对称类型的规范节点对查询忽略端点顺序。 */
    @Test
    void canonicalPairLookupIgnoresEndpointOrder() {
        relationRepository.insertActiveOrThrowDuplicate(
                project.id(), nodeA.id(), nodeB.id(), NodeRelationType.RELATED_TO,
                NodeRelation.Origin.USER, null, null);

        assertThat(relationRepository.findActiveByCanonicalPair(
                project.id(), nodeA.id(), nodeB.id(), NodeRelationType.RELATED_TO)).isPresent();
        // 顺序反转——仍是同一个规范事实。
        assertThat(relationRepository.findActiveByCanonicalPair(
                project.id(), nodeB.id(), nodeA.id(), NodeRelationType.RELATED_TO)).isPresent();
        // 不同类型不得命中。
        assertThat(relationRepository.findActiveByCanonicalPair(
                project.id(), nodeA.id(), nodeB.id(), NodeRelationType.CONFLICTS_WITH)).isEmpty();
    }

    /**
     * 仓储层会自行规范化对称关系的端点,因此反方向写入会被当作受控冲突拒绝
     * (而不是原始 500),且落库的记录处于规范顺序。
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
     * V18 Step 2:迁移的 UPDATE 会把历史上以反序存储的单条 ACTIVE 对称关系
     * 规范化为 (min, max)。这里在可回滚事务内对种子数据执行同一 UPDATE
     * 来验证该转换。
     */
    @Test
    void migrationCanonicalizesReverseSingleActiveRow() throws Exception {
        // 迁移的规范方向是数据库自身的 uuid 排序(LEAST/GREATEST),对某些
        // 随机 id 而言可能与 Java 的 UUID.compareTo 不同。这里先用 SQL 计算
        // 数据库视角的规范节点对,再以"反方向"写入种子行,从而让交换效果
        // 一定可观察,与具体节点 id 无关。
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

        // 与迁移 Step 2 完全一致的转换,但只限定在本测试的项目范围内,以免
        // 提交时影响其他测试的数据。转换逻辑(把反序的 ACTIVE 对称行交换为
        // min/max)与 V18 的无范围 UPDATE 相同。
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
     * V18 Step 1(预检)在同一个无序节点对上已存在多条 ACTIVE 对称关系时必须
     * 快速失败。验证方式:先删除对称索引,种子写入两条反序的 ACTIVE 记录,
     * 再执行迁移的预检守卫,断言其抛出带可操作信息的错误。全部操作会回滚,
     * 共享测试库不受影响。
     */
    @Test
    void preflightFailsWhenDuplicateActiveSymmetricRelationExists() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                // 移除兜底索引,以便种子写入冲突的节点对。
                st.execute("DROP INDEX IF EXISTS idx_node_relations_symmetric_active_unique");

                // 同一无序节点对上的两条 ACTIVE RELATED_TO 记录,方向相反。
                // 使用本测试真实的项目和节点(外键安全);预检按项目 + 节点对分组。
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
     * 回归:PostgreSQL 的 uuid 排序(按 RFC 4122 字节序、无符号)并不保证与
     * Java 的 {@code UUID.compareTo}(两个有符号 64 位半段)一致。被 V18
     * 迁移规范化过的记录以数据库自身的顺序存储节点对;因此应用层的重复
     * 预检查必须使用与顺序无关的规范节点对查询,否则会漏掉迁移后的记录,
     * 并把唯一索引的原始 {@link DuplicateKeyException} 暴露出来,而不是
     * 抛出受控的 "already exists" 冲突。
     */
    @Test
    void javaAndPostgresOrderingDivergenceIsRejectedAsControlledConflict() {
        // 刻意构造排序分歧的节点对:A 的前 64 位半段最高位为 1,Java 判定
        // A < B(有符号),而 PostgreSQL 比较 A 的首字节 0x80 > B 的首字节
        // 0x00,判定 A > B。
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

        // 按迁移后的状态种子写入该节点对:记录已按数据库自身顺序规范化
        // (source = 数据库的 LEAST,target = 数据库的 GREATEST)——
        // 这正是 V18 Step 2 对此类分歧节点对留下的结果。
        seedNodeRow(a, "KNOWLEDGE");
        seedNodeRow(b, "KNOWLEDGE");
        jdbcTemplate.update(
                "INSERT INTO node_relations (id, project_id, source_node_id, target_node_id, "
                        + "relation_type, origin, status, created_at) "
                        + "VALUES (?, ?, ?, ?, 'RELATED_TO', 'USER', 'ACTIVE', NOW())",
                UUID.randomUUID(), project.id(), b, a);

        // 应用层无论从哪个方向正常创建,都必须得到受控的领域冲突,
        // 绝不能是原始 DuplicateKeyException。
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

        // 恰好剩一条 ACTIVE 关系,且保持迁移建立的数据库规范方向不变。
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

package com.specagent.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:MigrationV94V95CompatibilityTest.java
 *
 * 测试目标:在一个**隔离的、一次性的非空数据库**上验证 V94 与 V95 的升级路径,
 * 绝不触碰用户开发库({@code spec_agent})或既有测试库。
 *
 * 流程:新建临时库 → 迁移到 V93 → 灌入代表性存量数据 → 迁移到 head(V94/V95)
 * → 校验结构、数据完整性、查询行为与索引可用性 → 删除临时库。
 *
 * V94(CJK 词法通道):
 *   - {@code search_vector} 仍是生成列,GIN 索引重建成功;
 *   - 存量文档/来源/embedding 数据不丢失(逐行比对 + 表集合不变);
 *   - ASCII 查询与旧 {@code plainto_tsquery('simple', …)} 行为等价;
 *   - 中文改写查询经 {@code cjk_tsquery} 可召回;
 *   - 关闭 seqscan 后查询计划必须走 GIN 索引。
 *
 * V95(Zen 可选密钥):
 *   - 旧的非空 API Key 逐字节保留,仍可读取;
 *   - 新的无 Key 免费模型配置(api_key/masked_suffix 为 NULL)可持久化;
 *   - 空字符串 Key 不破坏读写;付费模型仍需 Key 由应用层校验(此处只验 schema 可空)。
 */
@SpringBootTest
@ActiveProfiles("test")
class MigrationV94V95CompatibilityTest {

    private static final int ENTRY_COUNT = 2000;

    @Autowired
    private Environment environment;

    @Test
    void upgradesV93DatabaseThroughV94AndV95WithoutDataLoss() throws Exception {
        String baseUrl = environment.getProperty("spring.datasource.url");
        String user = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");
        assertThat(baseUrl).isNotNull();

        String location = baseUrl.substring("jdbc:postgresql://".length());
        String hostPort = location.substring(0, location.indexOf('/'));
        String adminUrl = "jdbc:postgresql://" + hostPort + "/postgres";
        String tempDb = "spec_agent_mig_verify_" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 10);
        String tempUrl = "jdbc:postgresql://" + hostPort + "/" + tempDb;

        try (Connection admin = DriverManager.getConnection(adminUrl, user, password);
             Statement statement = admin.createStatement()) {
            admin.setAutoCommit(true);
            statement.execute("CREATE DATABASE " + tempDb);
        }

        try {
            // ---- 1) 迁移到 V93(升级前基线) ----
            Flyway.configure()
                    .dataSource(tempUrl, user, password)
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("93"))
                    .load()
                    .migrate();

            UUID corpusId = UUID.randomUUID();
            UUID orgId = UUID.randomUUID();
            UUID readyEntryId = UUID.randomUUID();
            UUID pendingEntryId = UUID.randomUUID();
            insertPreUpgradeFixture(tempUrl, user, password, corpusId, orgId,
                    readyEntryId, pendingEntryId);

            long entriesBefore = count(tempUrl, user, password,
                    "SELECT count(*) FROM retrieval_entries");
            long tablesBefore = count(tempUrl, user, password, """
                    SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'
                    """);
            assertThat(entriesBefore).isEqualTo(ENTRY_COUNT + 2);

            assertThat(queryString(tempUrl, user, password,
                    "SELECT api_key FROM opencode_settings WHERE singleton_id = 1"))
                    .isEqualTo("old-secret-key-xyz9");
            assertThat(queryString(tempUrl, user, password,
                    "SELECT masked_suffix FROM opencode_settings WHERE singleton_id = 1"))
                    .isEqualTo("xyz9");

            // ---- 2) 迁移到 head,并计时 V93 -> head 的存量重建 ----
            long upgradeStart = System.nanoTime();
            Flyway.configure()
                    .dataSource(tempUrl, user, password)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            long upgradeMs = (System.nanoTime() - upgradeStart) / 1_000_000L;
            System.out.println("[V94/V95] V93->head migration on "
                    + entriesBefore + " retrieval rows took " + upgradeMs + " ms");

            // ---- V94:结构 ----
            assertThat(queryString(tempUrl, user, password, """
                    SELECT is_generated FROM information_schema.columns
                    WHERE table_name = 'retrieval_entries' AND column_name = 'search_vector'
                    """)).isEqualTo("ALWAYS");
            assertThat(queryString(tempUrl, user, password, """
                    SELECT indexdef FROM pg_indexes
                    WHERE tablename = 'retrieval_entries'
                      AND indexname = 'idx_retrieval_entries_search_vector'
                    """)).contains("USING gin");
            assertThat(count(tempUrl, user, password, """
                    SELECT count(*) FROM pg_proc WHERE proname IN ('cjk_bigrams', 'cjk_tsquery')
                    """)).isEqualTo(2L);

            // ---- V94:数据完整性 ----
            assertThat(count(tempUrl, user, password,
                    "SELECT count(*) FROM retrieval_entries")).isEqualTo(entriesBefore);
            assertThat(count(tempUrl, user, password, """
                    SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'
                    """)).isEqualTo(tablesBefore);
            assertThat(queryString(tempUrl, user, password,
                    "SELECT content FROM retrieval_entries WHERE id = '" + readyEntryId + "'"))
                    .isEqualTo("用户取消后不得继续追加回答正文。规则编号 REQ-107。");
            assertThat(queryString(tempUrl, user, password,
                    "SELECT source_ref FROM retrieval_entries WHERE id = '" + readyEntryId + "'"))
                    .isEqualTo("help:cancel-rule");
            assertThat(queryString(tempUrl, user, password,
                    "SELECT source_version FROM retrieval_entries WHERE id = '" + readyEntryId + "'"))
                    .isNotBlank();
            assertThat(queryString(tempUrl, user, password,
                    "SELECT embedding_status FROM retrieval_entries WHERE id = '" + readyEntryId + "'"))
                    .isEqualTo("READY");
            assertThat(queryString(tempUrl, user, password,
                    "SELECT embedding::text FROM retrieval_entries WHERE id = '" + readyEntryId + "'"))
                    .isEqualTo("[0.1,0.2,0.3]");
            assertThat(queryString(tempUrl, user, password,
                    "SELECT content_hash FROM retrieval_entries WHERE id = '" + readyEntryId + "'"))
                    .isEqualTo("hash-ready");

            // 重建后的生成列对存量行全部生效
            assertThat(count(tempUrl, user, password,
                    "SELECT count(*) FROM retrieval_entries WHERE search_vector IS NOT NULL"))
                    .isEqualTo(entriesBefore);

            // ---- V94:ASCII 查询行为无回归(与旧 plainto 完全等价) ----
            assertThat(queryString(tempUrl, user, password,
                    "SELECT (cjk_tsquery('status advice REQ-107')::text"
                            + " = plainto_tsquery('simple','status advice REQ-107')::text)::text"))
                    .isEqualTo("true");

            // ---- V94:中文改写经 cjk_tsquery 可召回 ----
            long chineseHits = count(tempUrl, user, password,
                    "SELECT count(*) FROM retrieval_entries WHERE search_vector"
                            + " @@ cjk_tsquery('终止对话后的正文有什么约束？')");
            assertThat(chineseHits).isGreaterThan(0L);

            // ---- V94:GIN 索引可用(关闭 seqscan 后计划必须走索引) ----
            String plan = explainWithSeqScanOff(tempUrl, user, password,
                    "SELECT id FROM retrieval_entries WHERE search_vector"
                            + " @@ cjk_tsquery('终止对话后的正文有什么约束？')");
            assertThat(plan).contains("idx_retrieval_entries_search_vector");

            long queryMs = measureQueryMs(tempUrl, user, password);
            System.out.println("[V94] cjk_tsquery p50 over " + entriesBefore
                    + " rows: " + queryMs + " ms; chineseHits=" + chineseHits
                    + "; asciiParity=true; ginIndexUsed=true");

            // ---- V95:可选密钥 ----
            assertThat(queryString(tempUrl, user, password, """
                    SELECT is_nullable FROM information_schema.columns
                    WHERE table_name = 'opencode_settings' AND column_name = 'api_key'
                    """)).isEqualTo("YES");
            assertThat(queryString(tempUrl, user, password, """
                    SELECT is_nullable FROM information_schema.columns
                    WHERE table_name = 'opencode_settings' AND column_name = 'masked_suffix'
                    """)).isEqualTo("YES");
            // 旧的非空 Key 迁移后逐字节保留(仍可读取;解密由应用层负责,此处验证密文不变)
            assertThat(queryString(tempUrl, user, password,
                    "SELECT api_key FROM opencode_settings WHERE singleton_id = 1"))
                    .isEqualTo("old-secret-key-xyz9");
            assertThat(queryString(tempUrl, user, password,
                    "SELECT masked_suffix FROM opencode_settings WHERE singleton_id = 1"))
                    .isEqualTo("xyz9");
            // 新的免费模型配置(无 Key)可写入并读回 NULL
            execute(tempUrl, user, password, """
                    INSERT INTO opencode_settings
                        (singleton_id, api_key, masked_suffix, selected_model,
                         created_at, updated_at, org_id)
                    VALUES (1, NULL, NULL, 'mimo-v2.6-flash-free', NOW(), NOW(), '%s')
                    ON CONFLICT (org_id, singleton_id) DO UPDATE
                        SET api_key = NULL, masked_suffix = NULL,
                            selected_model = 'mimo-v2.6-flash-free'
                    """.formatted(orgId));
            assertThat(count(tempUrl, user, password, """
                    SELECT count(*) FROM opencode_settings
                    WHERE api_key IS NULL AND masked_suffix IS NULL
                    """)).isEqualTo(1L);
            // 空字符串 Key 不破坏读写
            execute(tempUrl, user, password,
                    "UPDATE opencode_settings SET api_key = '', masked_suffix = ''"
                            + " WHERE singleton_id = 1 AND org_id = '" + orgId + "'");
            assertThat(queryString(tempUrl, user, password,
                    "SELECT api_key FROM opencode_settings WHERE org_id = '" + orgId + "'"))
                    .isEmpty();
        } finally {
            try (Connection admin = DriverManager.getConnection(adminUrl, user, password);
                 Statement statement = admin.createStatement()) {
                admin.setAutoCommit(true);
                statement.execute("DROP DATABASE IF EXISTS " + tempDb + " WITH (FORCE)");
            }
        }
    }

    /**
     * 灌入升级前的存量数据。使用 HELP 语料路径(project_id 允许为 NULL),
     * 因此不需要为临时库再造组织/用户/项目的外键链。
     */
    private static void insertPreUpgradeFixture(String url, String user, String password,
                                                UUID corpusId, UUID orgId, UUID readyEntryId,
                                                UUID pendingEntryId) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            // opencode_settings.org_id 外键到 organizations;造一个最小组织即可。
            // V93 基线上该表用 name(=后续重命名为 display_name),kind/status 有默认值。
            statement.execute("INSERT INTO organizations (id, name, status,"
                    + " created_at, updated_at) VALUES ('" + orgId
                    + "', 'migration verify org', 'ACTIVE', NOW(), NOW())");
            statement.execute("INSERT INTO retrieval_entries (id, project_id, corpus_id,"
                    + " source_kind, source_id, source_ref, scope, authority, content,"
                    + " content_hash, embedding, embedding_model, embedding_dimensions,"
                    + " embedding_status)"
                    + " VALUES ('" + readyEntryId + "', NULL, '" + corpusId
                    + "', 'HELP_CHUNK', '" + UUID.randomUUID() + "', 'help:cancel-rule',"
                    + " 'HELP', 'CANONICAL', '用户取消后不得继续追加回答正文。规则编号 REQ-107。',"
                    + " 'hash-ready', '[0.1,0.2,0.3]'::vector, 'test-embed', 3, 'READY')");
            statement.execute("INSERT INTO retrieval_entries (id, project_id, corpus_id,"
                    + " source_kind, source_id, source_ref, scope, authority, content,"
                    + " content_hash, embedding_status) VALUES ('" + pendingEntryId
                    + "', NULL, '" + corpusId + "', 'HELP_CHUNK', '" + UUID.randomUUID()
                    + "', 'help:pending', 'HELP', 'CANONICAL', '普通员工都可以预约会议室，"
                    + "但是大型会议室需要管理员审批。', 'hash-pending', 'PENDING')");

            // 批量填充较大语料,验证重建在多行上的行为(不是只测 52 条样本)
            try (PreparedStatement batch = connection.prepareStatement(
                    "INSERT INTO retrieval_entries (id, project_id, corpus_id, source_kind,"
                            + " source_id, source_ref, scope, authority, content, content_hash,"
                            + " embedding_status) VALUES (?, NULL, ?, 'HELP_CHUNK', ?, ?,"
                            + " 'HELP', 'DERIVED', ?, ?, 'PENDING')")) {
                for (int index = 0; index < ENTRY_COUNT; index++) {
                    batch.setObject(1, UUID.randomUUID());
                    batch.setObject(2, corpusId);
                    batch.setObject(3, UUID.randomUUID());
                    batch.setString(4, "help:bulk-" + index);
                    batch.setString(5, "这是第 " + index + " 条中文规则正文，用于验证词法通道重建。REQ-"
                            + (100 + index));
                    batch.setString(6, "hash-bulk-" + index);
                    batch.addBatch();
                    if (index % 500 == 499) {
                        batch.executeBatch();
                    }
                }
                batch.executeBatch();
            }

            // 旧的非空 API Key(升级前写入)
            statement.execute("INSERT INTO opencode_settings (singleton_id, api_key,"
                    + " masked_suffix, selected_model, org_id) VALUES (1, 'old-secret-key-xyz9',"
                    + " 'xyz9', 'legacy-paid-model', '" + orgId + "')");
        }
    }

    private static String explainWithSeqScanOff(String url, String user, String password,
                                                String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("SET enable_seqscan = off");
            try (ResultSet rows = statement.executeQuery("EXPLAIN (FORMAT TEXT) " + sql)) {
                StringBuilder plan = new StringBuilder();
                while (rows.next()) {
                    plan.append(rows.getString(1)).append('\n');
                }
                return plan.toString();
            }
        }
    }

    private static long measureQueryMs(String url, String user, String password) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            String sql = "SELECT count(*) FROM retrieval_entries WHERE search_vector"
                    + " @@ cjk_tsquery('终止对话后的正文有什么约束？')";
            statement.executeQuery(sql).close();
            long[] samples = new long[20];
            for (int index = 0; index < samples.length; index++) {
                long begin = System.nanoTime();
                statement.executeQuery(sql).close();
                samples[index] = (System.nanoTime() - begin) / 1_000_000L;
            }
            java.util.Arrays.sort(samples);
            return samples[samples.length / 2];
        }
    }

    private static long count(String url, String user, String password, String sql)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static String queryString(String url, String user, String password, String sql)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static void execute(String url, String user, String password, String sql)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}

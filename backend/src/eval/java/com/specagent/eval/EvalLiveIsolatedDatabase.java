package com.specagent.eval;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 文件名:EvalLiveIsolatedDatabase.java
 *
 * 用途:P3-B live 评测对"隔离数据库身份"的唯一守卫与种子入口。
 *
 * <p><b>为什么需要它</b>:live 套件在开跑前要把激活提供商与选中模型重置成固定的
 * Kilo 目标(先 {@code DELETE} 再 {@code INSERT})。虽然默认地址指向隔离库,
 * {@code SPEC_AGENT_EVAL_DB_URL} 可以把它覆盖成任何地址——一旦覆盖成用户开发库,
 * 这些 {@code DELETE} 就会造成不可逆的数据破坏。<b>只看环境变量名不足以判定安全</b>:
 * 这里在<b>任何写操作之前</b>向服务器查询<b>真实连接的库身份</b>,只要不是被明确
 * 指定的独立 P3-B live 评测库就立即失败关闭,一行数据也不改。
 *
 * <p>被接受的库名必须同时满足:
 * <ol>
 *   <li>匹配 {@code spec_agent_p3b_live_<后缀>} —— 只有显式的 live 隔离库;并且</li>
 *   <li>与当前数据源配置中的库名完全一致 —— 防止"环境变量写的是隔离库、实际连到的
 *       却是别的库"这种连接漂移;并且</li>
 *   <li>不在显式拒绝名单里({@code spec_agent}、{@code spec_agent_test}、
 *       {@code spec_agent_p3a} 等普通开发/测试库)。</li>
 * </ol>
 */
public final class EvalLiveIsolatedDatabase {

    /** 被允许的独立 live 评测库命名(仅这些库可以被种子重置)。 */
    private static final Pattern LIVE_EVAL_DATABASE =
            Pattern.compile("^spec_agent_p3b_live_[a-z0-9_]+$");

    /** 绝不允许被种子重置的库:用户开发库、共享测试库与历史 P3-A/P3-B 库。 */
    private static final List<String> FORBIDDEN_DATABASES = List.of(
            "spec_agent",
            "spec_agent_test",
            "spec_agent_p3a",
            "spec_agent_p3b",
            "postgres",
            "template0",
            "template1");

    private EvalLiveIsolatedDatabase() {
    }

    /** 真实连接的库身份(全部取自服务器端,不信任任何客户端字符串)。 */
    public record DatabaseIdentity(String database, String user, String host, int port) {

        public String describe() {
            return user + "@" + host + ":" + port + "/" + database;
        }
    }

    /**
     * 读取服务器端的真实库身份。无法判定身份时也失败关闭——绝不在身份未知的情况下
     * 继续任何写操作。
     */
    public static DatabaseIdentity readIdentity(JdbcTemplate jdbcTemplate) {
        DatabaseIdentity identity = jdbcTemplate.query(
                "SELECT current_database() AS db, current_user AS usr, "
                        + "coalesce(inet_server_addr()::text, 'local') AS host, "
                        + "coalesce(inet_server_port(), -1) AS port",
                resultSet -> resultSet.next()
                        ? new DatabaseIdentity(
                                resultSet.getString("db"),
                                resultSet.getString("usr"),
                                resultSet.getString("host"),
                                resultSet.getInt("port"))
                        : null);
        if (identity == null || identity.database() == null || identity.database().isBlank()) {
            throw new IllegalStateException(
                    "refusing to seed: the connected database identity is undeterminable");
        }
        return identity;
    }

    /** 仅按真实库身份判定是否允许种子重置;不允许即抛错(调用方必须在任何写之前调用)。 */
    public static void requireIsolatedLiveEvalDatabase(DatabaseIdentity identity) {
        requireIsolatedLiveEvalDatabase(identity, identity == null ? null : identity.database());
    }

    /**
     * 完整判定:真实库身份必须匹配 live 隔离库命名,且与配置里声明的库名一致。
     *
     * @param configuredDatabase 数据源配置解析出的库名;{@code null} 表示未声明
     * @throws IllegalStateException 任何一项不满足时(失败关闭,不改任何数据)
     */
    public static void requireIsolatedLiveEvalDatabase(DatabaseIdentity identity,
                                                       String configuredDatabase) {
        if (identity == null || identity.database() == null || identity.database().isBlank()) {
            throw new IllegalStateException(
                    "refusing to seed: the connected database identity is undeterminable");
        }
        if (configuredDatabase == null || configuredDatabase.isBlank()) {
            throw new IllegalStateException(
                    "refusing to seed: the configured database name is undeterminable");
        }
        String actual = identity.database().trim();
        String normalized = actual.toLowerCase(Locale.ROOT);
        if (FORBIDDEN_DATABASES.contains(normalized)) {
            throw new IllegalStateException("refusing to seed: " + identity.describe()
                    + " is an ordinary development/test database, not an isolated"
                    + " P3-B live evaluation database");
        }
        if (!LIVE_EVAL_DATABASE.matcher(normalized).matches()) {
            throw new IllegalStateException("refusing to seed: " + identity.describe()
                    + " is not a designated P3-B live evaluation database"
                    + " (expected spec_agent_p3b_live_*)");
        }
        if (!actual.equals(configuredDatabase.trim())) {
            throw new IllegalStateException("refusing to seed: the configured database "
                    + configuredDatabase + " does not match the connected database "
                    + identity.describe() + " — the connection is not the intended isolated"
                    + " evaluation database");
        }
    }

    /** 从 JDBC url 解析库名(去掉查询参数);无法解析时返回 {@code null}。 */
    public static String databaseNameFromUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return null;
        }
        String withoutQuery = jdbcUrl.split("\\?", 2)[0];
        int slash = withoutQuery.lastIndexOf('/');
        if (slash < 0 || slash == withoutQuery.length() - 1) {
            return null;
        }
        return withoutQuery.substring(slash + 1);
    }

    /**
     * 隔离库准备:在<b>任何 DELETE / INSERT 之前</b>先核实真实库身份,仅在被明确
     * 指定的独立 P3-B live 评测库上激活 Kilo 并把固定模型写入 {@code kilo_settings}。
     * 绝不触碰用户开发库。
     *
     * @return 已核实的真实库名(供报告留证)
     */
    public static String seedKiloActivation(JdbcTemplate jdbcTemplate,
                                            String configuredDatabase,
                                            String kiloModel,
                                            UUID orgId) {
        // Check first on the caller's current connection so rejected configurations remain
        // read-only even when no transaction-capable datasource is available.
        DatabaseIdentity initialIdentity = readIdentity(jdbcTemplate);
        requireIsolatedLiveEvalDatabase(initialIdentity, configuredDatabase);
        if (jdbcTemplate.getDataSource() == null) {
            throw new IllegalStateException("refusing to seed: the evaluation datasource is unavailable");
        }
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbcTemplate.getDataSource()));
        return transaction.execute(status -> {
            // Recheck inside the transaction in case a pool or routing datasource changed targets.
            DatabaseIdentity identity = readIdentity(jdbcTemplate);
            requireIsolatedLiveEvalDatabase(identity, configuredDatabase);

            jdbcTemplate.update("DELETE FROM kilo_settings");
            jdbcTemplate.update("DELETE FROM model_provider_settings");
            jdbcTemplate.update(
                    "INSERT INTO model_provider_settings "
                            + "(singleton_id, active_provider, updated_at, active_provider_id, org_id) "
                            + "VALUES (1, 'KILO', CURRENT_TIMESTAMP, NULL, ?)",
                    orgId);
            jdbcTemplate.update(
                    "INSERT INTO kilo_settings "
                            + "(org_id, singleton_id, api_key, masked_suffix, selected_model) "
                            + "VALUES (?, 1, NULL, NULL, ?)",
                    orgId, kiloModel);
            return identity.database();
        });
    }

    /**
     * 隔离库准备(自定义提供商版):同样在<b>任何 DELETE / INSERT 之前</b>先核实真实
     * 库身份,仅在被明确指定的独立 P3-B live 评测库上激活 CUSTOM 并写入单例
     * {@code custom_provider_settings} 行。用于把本地 Ollama(OpenAI 兼容)提供商接入
     * 同一条 live A/B 链路。密钥显式为 {@code NULL}:本地服务不需要凭据,也绝不会
     * 把任何历史密钥带到这个新地址。{@code validated_revision = config_revision = 1}
     * 表示该配置已通过兼容性验证、处于可激活状态。
     *
     * @return 已核实的真实库名(供报告留证)
     */
    public static String seedCustomActivation(JdbcTemplate jdbcTemplate,
                                              String configuredDatabase,
                                              String apiFormat,
                                              String baseUrl,
                                              String selectedModel,
                                              String displayName,
                                              UUID orgId) {
        DatabaseIdentity initialIdentity = readIdentity(jdbcTemplate);
        requireIsolatedLiveEvalDatabase(initialIdentity, configuredDatabase);
        if (jdbcTemplate.getDataSource() == null) {
            throw new IllegalStateException("refusing to seed: the evaluation datasource is unavailable");
        }
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbcTemplate.getDataSource()));
        return transaction.execute(status -> {
            DatabaseIdentity identity = readIdentity(jdbcTemplate);
            requireIsolatedLiveEvalDatabase(identity, configuredDatabase);

            // 保留已存同 origin 的加密凭据与脱敏后缀(若存在)
            String existingKey = null;
            String existingMasked = null;
            try {
                var rows = jdbcTemplate.queryForList(
                        "SELECT api_key, masked_suffix, base_url FROM custom_provider_settings WHERE singleton_id = 1");
                if (!rows.isEmpty()) {
                    var row = rows.get(0);
                    if (baseUrl != null && baseUrl.equals(row.get("base_url"))) {
                        existingKey = (String) row.get("api_key");
                        existingMasked = (String) row.get("masked_suffix");
                    }
                }
            } catch (Exception ignored) {
            }

            jdbcTemplate.update("DELETE FROM kilo_settings");
            jdbcTemplate.update("DELETE FROM model_provider_settings");
            jdbcTemplate.update("DELETE FROM custom_provider_settings");
            jdbcTemplate.update(
                    "INSERT INTO model_provider_settings "
                            + "(singleton_id, active_provider, updated_at, active_provider_id, org_id) "
                            + "VALUES (1, 'CUSTOM', CURRENT_TIMESTAMP, NULL, ?)",
                    orgId);
            jdbcTemplate.update(
                    "INSERT INTO custom_provider_settings "
                            + "(org_id, singleton_id, api_format, base_url, api_key, masked_suffix, "
                            + "selected_model, model_source, display_name, config_revision, "
                            + "validated_revision, created_at, updated_at, validated_at) "
                            + "VALUES (?, 1, ?, ?, ?, ?, ?, 'MANUAL', ?, 1, 1, "
                            + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    orgId, apiFormat, baseUrl, existingKey, existingMasked, selectedModel, displayName);
            return identity.database();
        });
    }
}

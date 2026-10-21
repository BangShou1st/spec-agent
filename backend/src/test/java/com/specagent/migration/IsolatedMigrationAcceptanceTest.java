package com.specagent.migration;

import com.specagent.agent.runtime.LegacyBindingMigrationGuard;
import com.specagent.eval.EvalLiveIsolatedDatabase;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-PostgreSQL acceptance for the V98..V103 migration chain.
 *
 * <p>Every scenario runs against a disposable database created inside the
 * isolated acceptance server. The class refuses to run unless the configured
 * target already passed the project's own isolation gate
 * ({@link EvalLiveIsolatedDatabase#verifyBeforeContext}), so it can never touch
 * a developer or historical database.
 *
 * <p>The guard under test is the production {@link LegacyBindingMigrationGuard}.
 * Spring Boot registers every {@code org.flywaydb.core.api.callback.Callback}
 * bean automatically; a plain Flyway instance has to be given the same callback
 * explicitly, otherwise the V99 protection would silently be absent from this
 * test while still being present in the product.
 */
@Tag("database-integration")
@DisplayName("Isolated migration acceptance (V98..V103)")
class IsolatedMigrationAcceptanceTest {

    private static final String MIGRATIONS = "classpath:db/migration";
    private static final String LATEST = "103";

    private static String url;
    private static String user;
    private static String password;
    private static String adminUrl;
    private static String host;
    private static String port;

    @BeforeAll
    static void verifyIsolatedTarget() throws Exception {
        url = env("SPEC_AGENT_TEST_DB_URL");
        user = env("SPEC_AGENT_TEST_DB_USERNAME");
        password = env("SPEC_AGENT_TEST_DB_PASSWORD");
        host = env("SPEC_AGENT_TEST_DB_SERVER_ADDRESS");
        port = env("SPEC_AGENT_TEST_DB_SERVER_PORT");
        // The project's own isolation gate: refuses spec_agent / spec_agent_test and
        // pins the server-reported endpoint before anything is written.
        var identity = EvalLiveIsolatedDatabase.verifyBeforeContext(url, user, password, host, port);
        assertThat(identity.database()).isEqualTo(EvalLiveIsolatedDatabase.databaseNameFromUrl(url));
        assertThat(identity.port()).isEqualTo(Integer.parseInt(port));
        String location = url.substring("jdbc:postgresql://".length());
        String hostPort = location.substring(0, location.indexOf('/'));
        adminUrl = "jdbc:postgresql://" + hostPort + "/postgres";
    }

    // ------------------------------------------------------------------
    // Scenario A - fresh install
    // ------------------------------------------------------------------
    @Test
    @DisplayName("A: fresh install applies the whole chain and is re-entrant")
    void freshInstallAppliesWholeChainAndIsReEntrant() throws Exception {
        String db = scenarioDatabase("miga");
        dropIfExists(db);
        createDatabase(db);
        String target = urlOf(db);

        var result = migrate(target, LATEST);
        assertThat(result.migrationsExecuted).isEqualTo(102);

        // Flyway history
        assertThat(scalar(target, "select count(*) from flyway_schema_history where success"))
                .isEqualTo("102");
        assertThat(scalar(target, "select coalesce(max(installed_rank),0) from flyway_schema_history"))
                .isEqualTo("102");
        assertThat(scalar(target, "select count(*) from flyway_schema_history where version='103'"))
                .isEqualTo("1");

        // V98 - durable binding fingerprint column
        assertThat(columnExists(target, "agent_runs", "model_binding_fingerprint")).isTrue();
        assertThat(columnType(target, "agent_runs", "model_binding_fingerprint")).isEqualTo("character(64)");

        // V100 - immutability trigger
        assertThat(triggerExists(target, "agent_runs", "protect_agent_run_model_binding")).isTrue();

        // V101 - deferred deadline + partial index
        assertThat(columnExists(target, "capability_invocations", "pending_deadline")).isTrue();
        assertThat(indexExists(target, "idx_capability_invocations_pending_deadline")).isTrue();

        // V102 - wait projection + subscription tables
        assertThat(tableExists(target, "agent_run_dependency_projections")).isTrue();
        assertThat(tableExists(target, "agent_run_wait_subscriptions")).isTrue();
        assertThat(indexExists(target, "idx_wait_subscriptions_invocation")).isTrue();

        // V103 - generation identity + outcome facts
        assertThat(columnExists(target, "embedding_rebuilds", "readiness_revision")).isTrue();
        assertThat(tableExists(target, "embedding_rebuild_outcomes")).isTrue();
        assertThat(tableExists(target, "capability_rebuild_waits")).isTrue();
        assertThat(indexExists(target, "idx_capability_rebuild_wait_generation")).isTrue();

        // Re-entry must be a no-op.
        var again = migrate(target, LATEST);
        assertThat(again.migrationsExecuted).isZero();
        assertThat(scalar(target, "select count(*) from flyway_schema_history")).isEqualTo("102");
    }

    // ------------------------------------------------------------------
    // Scenario B - historical upgrade
    // ------------------------------------------------------------------
    @Test
    @DisplayName("B: upgrade from the pre-V98 baseline binds V98 then applies V99 fail-closed")
    void historicalUpgradeBindsV98ThenAppliesV99FailClosed() throws Exception {
        String db = scenarioDatabase("migb");
        dropIfExists(db);
        createDatabase(db);
        String target = urlOf(db);

        // Real historical baseline: everything up to V97 exists, V98 does not.
        migrate(target, "97");
        assertThat(columnExists(target, "agent_runs", "model_binding_fingerprint")).isFalse();

        Fixture fixture = seedFixture(target);

        // V98 adds the column; every historical row starts NULL.
        migrate(target, "98");
        assertThat(columnExists(target, "agent_runs", "model_binding_fingerprint")).isTrue();
        assertThat(fingerprint(target, fixture.createdRun())).isNull();
        assertThat(fingerprint(target, fixture.activeRun())).isNull();

        // The V98-era application binds the active run (that is the real deployment
        // window between shipping V98 and shipping V99).
        String digest = "a".repeat(64);
        execute(target, "update agent_runs set model_binding_fingerprint='" + digest
                + "' where id='" + fixture.activeRun() + "'");

        // V99 must refuse to destroy that binding.
        assertThatThrownBy(() -> migrate(target, LATEST))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V99_BINDING_OVERWRITE_BLOCKED");

        // The guard ran BEFORE V99's statement: V99 is not recorded and the
        // historical row keeps its binding.
        assertThat(scalar(target, "select count(*) from flyway_schema_history where version='99'"))
                .isEqualTo("0");
        // V17 does not exist in this chain, so the installed rank is offset from the
        // version number; the version itself is the authoritative marker.
        assertThat(scalar(target, "select coalesce(max(cast(version as numeric)),0) from flyway_schema_history"))
                .isEqualTo("98");
        assertThat(fingerprint(target, fixture.activeRun())).isEqualTo(digest);

        // Operator remediation: the blocked in-flight run is resolved (not forged), the
        // remaining historical rows are still upgradeable.
        execute(target, "update agent_runs set status='failed' where id='" + fixture.activeRun() + "'");
        migrate(target, LATEST);

        // V99 fail-closed backfill: only non-terminal, non-created runs are locked.
        // The resolved run is terminal now, so its existing binding is preserved.
        assertThat(fingerprint(target, fixture.activeRun())).isEqualTo(digest);
        // An active run that never bound a digest is locked to the sentinel.
        assertThat(fingerprint(target, fixture.unboundActiveRun())).isEqualTo("legacy-unbound");
        assertThat(fingerprint(target, fixture.createdRun())).isNull();
        assertThat(fingerprint(target, fixture.completedRun())).isNull();
        assertThat(fingerprint(target, fixture.failedRun())).isNull();
        assertThat(scalar(target, "select count(*) from flyway_schema_history where version='103'"))
                .isEqualTo("1");
    }

    @Test
    @DisplayName("B2: an illegal legacy fingerprint is blocked, not silently overwritten")
    void illegalLegacyFingerprintBlocksTheUpgrade() throws Exception {
        String db = scenarioDatabase("migb2");
        dropIfExists(db);
        createDatabase(db);
        String target = urlOf(db);

        migrate(target, "98");
        Fixture fixture = seedFixture(target);
        // A non-canonical, illegal digest on an active run.
        execute(target, "update agent_runs set model_binding_fingerprint='not-a-digest' where id='"
                + fixture.activeRun() + "'");

        assertThatThrownBy(() -> migrate(target, LATEST))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V99_BINDING_OVERWRITE_BLOCKED");
        assertThat(fingerprint(target, fixture.activeRun())).isEqualTo("not-a-digest");
        assertThat(scalar(target, "select count(*) from flyway_schema_history where version='99'"))
                .isEqualTo("0");
    }

    @Test
    @DisplayName("B3: an already legacy-unbound row is upgradeable and stays fail-closed")
    void legacyUnboundSentinelIsUpgradeable() throws Exception {
        String db = scenarioDatabase("migb3");
        dropIfExists(db);
        createDatabase(db);
        String target = urlOf(db);

        migrate(target, "98");
        Fixture fixture = seedFixture(target);
        execute(target, "update agent_runs set model_binding_fingerprint='legacy-unbound' where id='"
                + fixture.activeRun() + "'");

        migrate(target, LATEST);
        assertThat(fingerprint(target, fixture.activeRun())).isEqualTo("legacy-unbound");
        assertThat(scalar(target, "select count(*) from flyway_schema_history where version='99'"))
                .isEqualTo("1");
    }

    // ------------------------------------------------------------------
    // Scenario C - V100..V103 behaviour
    // ------------------------------------------------------------------
    @Test
    @DisplayName("C: V100 protects a bound fingerprint, V101/V102/V103 keep their shape")
    void v100ToV103Behaviour() throws Exception {
        String db = scenarioDatabase("migc");
        dropIfExists(db);
        createDatabase(db);
        String target = urlOf(db);

        migrate(target, LATEST);
        Fixture fixture = seedFixture(target);

        // V100: first NULL -> fingerprint binding is legal.
        execute(target, "update agent_runs set model_binding_fingerprint='" + "b".repeat(64)
                + "' where id='" + fixture.createdRun() + "'");
        // Same value again is legal.
        execute(target, "update agent_runs set model_binding_fingerprint='" + "b".repeat(64)
                + "' where id='" + fixture.createdRun() + "'");
        // Changing a bound fingerprint is refused by the trigger.
        assertThatThrownBy(() -> execute(target, "update agent_runs set model_binding_fingerprint='"
                + "c".repeat(64) + "' where id='" + fixture.createdRun() + "'"))
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> {
                    SQLException sql = (SQLException) failure;
                    assertThat(sql.getSQLState()).isEqualTo("23514");
                    assertThat(sql.getMessage()).contains("RUN_MODEL_BINDING_IMMUTABLE");
                });

        // V101: a pre-existing RUNNING invocation is not silently promoted.
        UUID invocation = UUID.randomUUID();
        execute(target, "insert into capability_invocations(id,capability_id,project_id,run_id,"
                + "invocation_key,status,created_at) values('" + invocation
                + "','retrieval.await_generation','" + fixture.project() + "','" + fixture.createdRun()
                + "','k-" + invocation + "','RUNNING',CURRENT_TIMESTAMP)");
        assertThat(scalar(target, "select pending_deadline::text is null from capability_invocations where id='"
                + invocation + "'")).isEqualTo("t");

        // V102: the subscription state domain is enforced.
        assertThatThrownBy(() -> execute(target,
                "insert into agent_run_wait_subscriptions(wait_run_id,project_id,invocation_id,"
                        + "snapshot_id,proposal_id,state) values('" + fixture.createdRun() + "','"
                        + fixture.project() + "','" + invocation + "','" + fixture.snapshot() + "','"
                        + UUID.randomUUID() + "','NOPE')"))
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("23514"));

        // V103: readiness_revision is not negative and the outcome identity is unique.
        String profile = seedEmbeddingProfile(target, fixture);
        UUID generation = UUID.randomUUID();
        execute(target, "insert into retrieval_index_generations(id,corpus_id,profile_id,state,version)"
                + " values('" + generation + "','" + fixture.project() + "','" + profile + "','PREPARING',1)");
        execute(target, "insert into embedding_rebuilds(id,corpus_id,profile_id,expected_head_version,state,org_id)"
                + " values('" + generation + "','" + fixture.project() + "','" + profile + "',0,'READY','"
                + fixture.org() + "')");
        assertThatThrownBy(() -> execute(target,
                "insert into embedding_rebuild_outcomes(generation_id,readiness_revision,org_id,project_id,state)"
                        + " select id, -1, org_id, project_id, 'READY' from embedding_rebuilds limit 1"))
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("23514"));

        // The failed insert left no row behind and did not damage the generation.
        assertThat(scalar(target, "select count(*) from embedding_rebuild_outcomes")).isEqualTo("0");
        assertThat(scalar(target, "select state from embedding_rebuilds where id='" + generation + "'"))
                .isEqualTo("READY");
    }

    @Test
    @DisplayName("C2: V103 backfills terminal project generations and keeps old writers working")
    void v103BackfillsTerminalGenerationsAndKeepsOldWriterCompatibility() throws Exception {
        String db = scenarioDatabase("migc2");
        dropIfExists(db);
        createDatabase(db);
        String target = urlOf(db);

        // Baseline just before V103.
        migrate(target, "102");
        Fixture fixture = seedFixture(target);
        String profile = seedEmbeddingProfile(target, fixture);
        UUID generation = UUID.randomUUID();

        execute(target, "insert into retrieval_index_generations(id,corpus_id,profile_id,state,version)"
                + " values('" + generation + "','" + fixture.project() + "','" + profile + "','PREPARING',1)");
        execute(target, "insert into embedding_rebuilds(id,corpus_id,profile_id,expected_head_version,state,org_id)"
                + " values('" + generation + "','" + fixture.project() + "','" + profile + "',0,'READY','"
                + fixture.org() + "')");
        // embedding_rebuild_outcomes only exists from V103 onwards.
        assertThat(scalar(target, "select coalesce(to_regclass('public.embedding_rebuild_outcomes')::text,'<absent>')"))
                .isEqualTo("<absent>");

        migrate(target, LATEST);

        // The published backfill observed the already-terminal project generation.
        assertThat(scalar(target, "select state from embedding_rebuild_outcomes where generation_id='"
                + generation + "'")).isEqualTo("READY");
        assertThat(scalar(target, "select readiness_revision::text from embedding_rebuild_outcomes where generation_id='"
                + generation + "'")).isEqualTo("0");

        // Old writer compatibility: an INSERT that does not know readiness_revision
        // still succeeds and lands on the default revision.
        UUID legacyGeneration = UUID.randomUUID();
        execute(target, "insert into retrieval_index_generations(id,corpus_id,profile_id,state,version)"
                + " values('" + legacyGeneration + "','" + fixture.project() + "','" + profile + "','PREPARING',1)");
        execute(target, "insert into embedding_rebuilds(id,corpus_id,profile_id,expected_head_version,state,org_id)"
                + " values('" + legacyGeneration + "','" + fixture.project() + "','" + profile + "',0,'QUEUED','"
                + fixture.org() + "')");
        assertThat(scalar(target, "select readiness_revision::text from embedding_rebuilds where id='"
                + legacyGeneration + "'")).isEqualTo("0");

        // A retry attempt reuses the generation id but starts a new readiness revision.
        execute(target, "update embedding_rebuilds set state='FAILED' where id='" + legacyGeneration + "'");
        execute(target, "update embedding_rebuilds set state='QUEUED', readiness_revision=readiness_revision+1"
                + " where id='" + legacyGeneration + "'");
        assertThat(scalar(target, "select readiness_revision::text from embedding_rebuilds where id='"
                + legacyGeneration + "'")).isEqualTo("1");
    }

    // ------------------------------------------------------------------
    // Scenario D - failure and recovery
    // ------------------------------------------------------------------
    @Test
    @DisplayName("D: a failing migration rolls back completely and can be retried")
    void failingMigrationRollsBackAndCanBeRetried(@TempDir Path temp) throws Exception {
        String db = scenarioDatabase("migd");
        dropIfExists(db);
        createDatabase(db);
        String target = urlOf(db);

        migrate(target, LATEST);

        // A synthetic, forward-only migration that fails inside its transaction.
        Path extra = temp.resolve("extra");
        Files.createDirectories(extra);
        Files.writeString(extra.resolve("V104__isolated_acceptance_failure.sql"),
                "create table isolated_acceptance_probe(id integer primary key);\n"
                        + "insert into isolated_acceptance_probe(id) values (1);\n"
                        + "select 1/0;\n");

        Flyway failing = Flyway.configure().dataSource(target, user, password)
                .locations(MIGRATIONS, filesystemLocation(extra))
                .callbacks(new LegacyBindingMigrationGuard())
                .load();
        assertThatThrownBy(failing::migrate).isInstanceOf(FlywayException.class);

        // PostgreSQL DDL is transactional: the partially created table is gone and
        // the schema history still stops at 103.
        assertThat(tableExists(target, "isolated_acceptance_probe")).isFalse();
        assertThat(scalar(target, "select count(*) from flyway_schema_history where version='104'"))
                .isEqualTo("0");
        assertThat(scalar(target, "select coalesce(max(installed_rank),0) from flyway_schema_history"))
                .isEqualTo("102");

        // Retry after the failure is removed succeeds.
        Files.delete(extra.resolve("V104__isolated_acceptance_failure.sql"));
        Path healthy = temp.resolve("healthy");
        Files.createDirectories(healthy);
        Files.writeString(healthy.resolve("V104__isolated_acceptance_success.sql"),
                "create table isolated_acceptance_probe(id integer primary key);\n");
        Flyway retry = Flyway.configure().dataSource(target, user, password)
                .locations(MIGRATIONS, filesystemLocation(healthy))
                .callbacks(new LegacyBindingMigrationGuard())
                .load();
        retry.migrate();
        assertThat(tableExists(target, "isolated_acceptance_probe")).isTrue();
        assertThat(scalar(target, "select count(*) from flyway_schema_history where version='104'"))
                .isEqualTo("1");
    }

    @Test
    @DisplayName("D2: an interrupted transaction leaves no partial state and keeps the data")
    void interruptedTransactionLeavesNoPartialState() throws Exception {
        String db = scenarioDatabase("migd2");
        dropIfExists(db);
        createDatabase(db);
        String target = urlOf(db);

        migrate(target, LATEST);
        Fixture fixture = seedFixture(target);

        // A connection that is dropped mid-transaction must not persist anything.
        try (Connection connection = DriverManager.getConnection(target, user, password)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("insert into agent_runs(id,project_id,trigger_type,status,created_at) values('"
                        + UUID.randomUUID() + "','" + fixture.project()
                        + "','initial_requirement','created',CURRENT_TIMESTAMP)");
            }
            // no commit: close rolls the transaction back
        }
        assertThat(scalar(target, "select count(*) from agent_runs where status='created'"))
                .isEqualTo("1");

        // A constraint conflict fails the statement but preserves the other rows.
        assertThatThrownBy(() -> execute(target, "insert into agent_runs(id,project_id,trigger_type,status,created_at)"
                + " values('" + fixture.createdRun() + "','" + fixture.project()
                + "','initial_requirement','created',CURRENT_TIMESTAMP)"))
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("23505"));
        assertThat(scalar(target, "select count(*) from agent_runs where status='created'"))
                .isEqualTo("1");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private record Fixture(UUID org, UUID user, UUID project, UUID snapshot,
                            UUID createdRun, UUID activeRun, UUID completedRun, UUID failedRun,
                            UUID unboundActiveRun) {}

    private static Fixture seedFixture(String target) throws SQLException {
        UUID org = UUID.randomUUID();
        UUID appUser = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID snapshot = UUID.randomUUID();
        UUID created = UUID.randomUUID();
        UUID active = UUID.randomUUID();
        UUID completed = UUID.randomUUID();
        UUID failed = UUID.randomUUID();
        UUID unboundActive = UUID.randomUUID();

        execute(target, "insert into organizations(id,name) values('" + org + "','migration acceptance org')");
        execute(target, "insert into app_users(id,display_name) values('" + appUser + "','migration acceptance user')");
        execute(target, "insert into projects(id,title,org_id,created_by) values('" + project
                + "','migration acceptance project','" + org + "','" + appUser + "')");
        // A context snapshot is required by the V102 subscription table.
        execute(target, "insert into context_snapshots(id,project_id,operation_type,context_hash,created_at)"
                + " values('" + snapshot + "','" + project + "','initial_requirement','"
                + "0".repeat(64) + "',CURRENT_TIMESTAMP)");
        for (var run : new UUID[]{created, active, completed, failed, unboundActive}) {
            execute(target, "insert into agent_runs(id,project_id,trigger_type,status,created_at) values('" + run
                    + "','" + project + "','initial_requirement','created',CURRENT_TIMESTAMP)");
        }
        execute(target, "update agent_runs set status='model_called' where id='" + active + "'");
        execute(target, "update agent_runs set status='model_called' where id='" + unboundActive + "'");
        execute(target, "update agent_runs set status='completed' where id='" + completed + "'");
        execute(target, "update agent_runs set status='failed' where id='" + failed + "'");
        return new Fixture(org, appUser, project, snapshot, created, active, completed, failed, unboundActive);
    }

    private static String seedEmbeddingProfile(String target, Fixture fixture) throws SQLException {
        // embedding_profiles.profile_id must be 64 lowercase hex characters, and the
        // registration is bound to an existing service revision.
        String profile = "0123456789abcdef".repeat(4);
        execute(target, "insert into embedding_service_revisions(revision,config,revoked,org_id)"
                + " values(1,'{}',false,'" + fixture.org() + "')");
        execute(target, "insert into embedding_profiles(profile_id,semantic,config,service_revision) values('"
                + profile + "','{}','{}',1)");
        execute(target, "insert into embedding_profile_registrations(org_id,profile_id,config,service_revision)"
                + " values('" + fixture.org() + "','" + profile + "','{}',1)");
        return profile;
    }

    private static String fingerprint(String target, UUID run) throws SQLException {
        // model_binding_fingerprint is CHAR(64): PostgreSQL compares bpchar ignoring
        // trailing blanks, but a JDBC string keeps them. Trim explicitly so the
        // assertion sees the logical value.
        return scalar(target, "select case when model_binding_fingerprint is null then null"
                + " else trim(model_binding_fingerprint) end from agent_runs where id='" + run + "'");
    }

    private static void execute(String target, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(target, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String scalar(String target, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(target, user, password);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private static boolean columnExists(String target, String table, String column) throws SQLException {
        return "t".equals(scalar(target, "select exists(select 1 from information_schema.columns where table_name='"
                + table + "' and column_name='" + column + "')"));
    }

    private static String columnType(String target, String table, String column) throws SQLException {
        return scalar(target, "select data_type||coalesce('('||character_maximum_length||')','')"
                + " from information_schema.columns where table_name='" + table + "' and column_name='" + column + "'");
    }

    private static boolean tableExists(String target, String table) throws SQLException {
        return "t".equals(scalar(target, "select exists(select 1 from information_schema.tables where table_name='"
                + table + "')"));
    }

    private static boolean indexExists(String target, String index) throws SQLException {
        return "t".equals(scalar(target, "select exists(select 1 from pg_indexes where indexname='" + index + "')"));
    }

    private static boolean triggerExists(String target, String table, String trigger) throws SQLException {
        return "t".equals(scalar(target, "select exists(select 1 from pg_trigger where not tgisinternal"
                + " and tgrelid='" + table + "'::regclass and tgname='" + trigger + "')"));
    }

    /** Runs the real Flyway chain with the production V99 guard registered. */
    private static MigrateResult migrate(String target, String targetVersion) {
        Flyway flyway = Flyway.configure().dataSource(target, user, password)
                .locations(MIGRATIONS)
                .callbacks(new LegacyBindingMigrationGuard())
                .target(MigrationVersion.fromVersion(targetVersion))
                .load();
        var result = flyway.migrate();
        return new MigrateResult(result.migrationsExecuted);
    }

    private record MigrateResult(int migrationsExecuted) {}

    private static void createDatabase(String database) throws SQLException {
        try (Connection connection = DriverManager.getConnection(adminUrl, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("create database " + database);
        }
    }

    private static void dropIfExists(String database) throws SQLException {
        try (Connection connection = DriverManager.getConnection(adminUrl, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("drop database if exists " + database + " with (force)");
        }
    }

    private static String scenarioDatabase(String suffix) {
        String base = EvalLiveIsolatedDatabase.databaseNameFromUrl(url);
        return base + "_" + suffix;
    }

    private static String urlOf(String database) {
        String location = url.substring("jdbc:postgresql://".length());
        String hostPort = location.substring(0, location.indexOf('/'));
        return "jdbc:postgresql://" + hostPort + "/" + database;
    }

    /** Flyway 10 only accepts the explicit {@code filesystem:} location prefix. */
    private static String filesystemLocation(Path directory) {
        return "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');
    }

    private static String env(String name) {        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing required environment variable: " + name
                    + " (the isolated acceptance target must be explicit)");
        }
        return value.trim();
    }
}

package com.specagent.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class MigrationV99ModelBindingBackfillTest {

    @Autowired private Environment environment;

    @Test
    void preexistingActiveRunsAreLockedWhileCreatedRunsCanBindAfterUpgrade() throws Exception {
        String baseUrl = environment.getProperty("spring.datasource.url");
        String user = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");
        assertThat(baseUrl).isNotNull();
        String location = baseUrl.substring("jdbc:postgresql://".length());
        String hostPort = location.substring(0, location.indexOf('/'));
        String adminUrl = "jdbc:postgresql://" + hostPort + "/postgres";
        String database = "spec_agent_v99_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String isolatedUrl = "jdbc:postgresql://" + hostPort + "/" + database;
        UUID organizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID activeRunId = UUID.randomUUID();
        UUID createdRunId = UUID.randomUUID();

        try (Connection admin = DriverManager.getConnection(adminUrl, user, password);
             Statement statement = admin.createStatement()) {
            admin.setAutoCommit(true);
            statement.execute("CREATE DATABASE " + database);
        }
        try {
            Flyway.configure().dataSource(isolatedUrl, user, password)
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("98"))
                    .load().migrate();

            try (Connection connection = DriverManager.getConnection(isolatedUrl, user, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO organizations(id, name) VALUES ('" + organizationId
                        + "', 'V99 migration fixture')");
                statement.execute("INSERT INTO app_users(id, display_name) VALUES ('" + userId
                        + "', 'V99 migration fixture')");
                statement.execute("INSERT INTO projects(id, title, org_id, created_by) VALUES ('"
                        + projectId + "', 'V99 migration fixture', '" + organizationId + "', '"
                        + userId + "')");
                insertRun(statement, activeRunId, projectId, "model_called");
                insertRun(statement, createdRunId, projectId, "created");
            }

            Flyway.configure().dataSource(isolatedUrl, user, password)
                    .locations("classpath:db/migration").load().migrate();

            assertThat(scalar(isolatedUrl, user, password,
                    "SELECT model_binding_fingerprint FROM agent_runs WHERE id = '" + activeRunId + "'"))
                    .startsWith("legacy-unbound");
            assertThat(scalar(isolatedUrl, user, password,
                    "SELECT model_binding_fingerprint IS NULL FROM agent_runs WHERE id = '" + createdRunId + "'"))
                    .isEqualTo("t");
        } finally {
            try (Connection admin = DriverManager.getConnection(adminUrl, user, password);
                 Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            }
        }
    }

    private static void insertRun(Statement statement, UUID runId, UUID projectId, String status)
            throws Exception {
        statement.execute("INSERT INTO agent_runs(id, project_id, trigger_type, status, created_at) VALUES ('"
                + runId + "', '" + projectId + "', 'initial_requirement', '" + status + "', CURRENT_TIMESTAMP)");
    }

    private static String scalar(String url, String user, String password, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }
}

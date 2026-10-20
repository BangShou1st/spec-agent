package com.specagent.support;

import com.specagent.eval.EvalLiveIsolatedDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.boot.test.util.TestPropertyValues;

import java.util.List;
import java.util.Map;

/** Test-only bootstrap gate: runs before the context loader, datasource, Flyway and runners. */
public final class DatabaseTestContextGuard implements ContextCustomizerFactory {
    public static final String ENABLED = "specagent.database-tests.enabled";
    private final java.util.function.Supplier<String> enabled;
    private final java.util.function.Function<String, String> environment;
    private final EvalLiveIsolatedDatabase.ConnectionFactory connections;

    public DatabaseTestContextGuard() {
        this(() -> System.getProperty(ENABLED), System::getenv, java.sql.DriverManager::getConnection);
    }

    DatabaseTestContextGuard(java.util.function.Supplier<String> enabled,
            java.util.function.Function<String, String> environment,
            EvalLiveIsolatedDatabase.ConnectionFactory connections) {
        this.enabled = enabled;
        this.environment = environment;
        this.connections = connections;
    }

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass,
            List<ContextConfigurationAttributes> configurationAttributes) {
        if (!AnnotatedElementUtils.hasAnnotation(testClass, SpringBootTest.class)
                && !AnnotatedElementUtils.hasAnnotation(testClass,
                        org.springframework.boot.test.autoconfigure.data.jdbc.DataJdbcTest.class)
                && !AnnotatedElementUtils.hasAnnotation(testClass,
                        org.springframework.boot.test.autoconfigure.jdbc.JdbcTest.class)
                && !AnnotatedElementUtils.hasAnnotation(testClass,
                        org.springframework.test.context.ContextConfiguration.class)
                && !AnnotatedElementUtils.hasAnnotation(testClass,
                        org.springframework.test.context.ContextHierarchy.class)
                && !AnnotatedElementUtils.hasAnnotation(testClass,
                        org.springframework.test.context.BootstrapWith.class)) return null;
        // Do not even inspect connection credentials until the explicit execution gate passes.
        requireEnabled(enabled.get());
        if (AnnotatedElementUtils.findMergedRepeatableAnnotations(testClass, org.junit.jupiter.api.Tag.class).stream()
                .anyMatch(tag -> tag.value().equals("external-service"))
                && !"true".equals(System.getProperty("specagent.external-tests.enabled")))
            throw new IllegalStateException("External-service Spring tests also require explicit -PexternalServices=true");
        String url = environment.apply("SPEC_AGENT_TEST_DB_URL");
        String user = environment.apply("SPEC_AGENT_TEST_DB_USERNAME");
        String password = environment.apply("SPEC_AGENT_TEST_DB_PASSWORD");
        EvalLiveIsolatedDatabase.verifyBeforeContext(url, user, password,
                environment.apply("SPEC_AGENT_TEST_DB_SERVER_ADDRESS"),
                environment.apply("SPEC_AGENT_TEST_DB_SERVER_PORT"), connections);
        return new VerifiedTarget(url, user, password);
    }

    static void requireEnabled(String enabled) {
        if (!"true".equals(enabled)) throw new IllegalStateException(
                "Database Spring tests require explicit -PdatabaseIntegration=true; use testOffline for offline regression");
    }

    record VerifiedTarget(String url, String user, String password) implements ContextCustomizer {
        // Never let record-generated diagnostics print database credentials.
        @Override public String toString() { return "VerifiedDatabaseTestTarget[redacted]"; }

        @Override public void customizeContext(org.springframework.context.ConfigurableApplicationContext context,
                org.springframework.test.context.MergedContextConfiguration mergedConfig) {
            TestPropertyValues.of(Map.of(
                    "spring.datasource.url", url, "spring.datasource.username", user,
                    "spring.datasource.password", password,
                    "spring.flyway.url", url, "spring.flyway.user", user,
                    "spring.flyway.password", password)).applyTo(context);
            // DynamicPropertySource may have higher precedence. Refuse drift before application beans.
            context.addBeanFactoryPostProcessor(ignored -> {
                var env = context.getEnvironment();
                for (var pair : Map.of("spring.datasource.url", url, "spring.datasource.username", user,
                        "spring.datasource.password", password, "spring.flyway.url", url,
                        "spring.flyway.user", user, "spring.flyway.password", password).entrySet()) {
                    if (!pair.getValue().equals(env.getProperty(pair.getKey())))
                        throw new IllegalStateException("Database test datasource/Flyway target drift; refusing initialization");
                }
            });
        }
    }
}

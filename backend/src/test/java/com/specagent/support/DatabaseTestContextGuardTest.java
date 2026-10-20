package com.specagent.support;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContextManager;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class DatabaseTestContextGuardTest {
    static final AtomicInteger STARTS = new AtomicInteger();

    @SpringBootTest(classes = SideEffectConfig.class)
    static class ForbiddenBootstrap {
        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            STARTS.incrementAndGet();
            throw new AssertionError("DynamicPropertySource must not run without opt-in");
        }
    }

    @org.junit.jupiter.api.Tag("external-service")
    @SpringBootTest(classes = SideEffectConfig.class)
    static class ExternalBootstrap { }

    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @org.junit.jupiter.api.Tag("external-service")
    @interface ExternalService { }

    @ExternalService
    @SpringBootTest(classes = SideEffectConfig.class)
    static class ComposedExternalBootstrap { }

    @org.springframework.test.context.ContextConfiguration(classes = SideEffectConfig.class)
    static class PlainSpringBootstrap { }

    @org.springframework.test.context.junit.jupiter.SpringJUnitConfig(SideEffectConfig.class)
    static class ComposedSpringBootstrap { }

    @org.springframework.test.context.ContextHierarchy(
            @org.springframework.test.context.ContextConfiguration(classes = SideEffectConfig.class))
    static class HierarchicalSpringBootstrap { }

    @org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
    static class SliceBootstrap { }

    @Configuration(proxyBeanMethods = false)
    static class SideEffectConfig {
        @Bean Object sideEffect() {
            STARTS.incrementAndGet();
            throw new AssertionError("application bean must not initialize");
        }
    }

    @Test void missingOrNonExactOptInFailsClosed() {
        for (String value : Arrays.asList(null, "", "false", "TRUE", "1"))
            assertThatThrownBy(() -> DatabaseTestContextGuard.requireEnabled(value))
                    .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> DatabaseTestContextGuard.requireEnabled("true")).doesNotThrowAnyException();
    }

    @Test void registeredFactoryRejectsBeforeDynamicPropertiesAndApplicationBeans() {
        STARTS.set(0);
        assertThat(System.getProperty(DatabaseTestContextGuard.ENABLED)).isEqualTo("false");
        assertThatThrownBy(() -> new TestContextManager(ForbiddenBootstrap.class))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("explicit -PdatabaseIntegration=true");
        assertThat(STARTS.get()).isZero();
    }

    @Test void disabledGateDoesNotReadEnvironmentOrRequestAnyConnection() {
        var guard = new DatabaseTestContextGuard(() -> "false",
                name -> { throw new AssertionError("must not inspect environment"); },
                (url, user, password) -> { throw new AssertionError("must not connect"); });
        for (Class<?> entry : java.util.List.of(ForbiddenBootstrap.class, SliceBootstrap.class))
            assertThatThrownBy(() -> guard.createContextCustomizer(entry, java.util.List.of()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("explicit");
    }

    @Test void plainComposedAndHierarchicalSpringEntrypointsRejectBeforeSideEffects() {
        STARTS.set(0);
        for (Class<?> entry : java.util.List.of(PlainSpringBootstrap.class,
                ComposedSpringBootstrap.class, HierarchicalSpringBootstrap.class)) {
            assertThatThrownBy(() -> new TestContextManager(entry))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("explicit -PdatabaseIntegration=true");
        }
        assertThat(STARTS.get()).isZero();
    }

    @Test void explicitOptInWithMissingOrSharedTargetNeverRequestsConnection() {
        for (String url : java.util.List.of("", "jdbc:postgresql://unused/spec_agent_test")) {
            var guard = new DatabaseTestContextGuard(() -> "true", name ->
                    name.equals("SPEC_AGENT_TEST_DB_URL") ? url : null,
                    (target, user, password) -> { throw new AssertionError("must not connect"); });
            assertThatThrownBy(() -> guard.createContextCustomizer(ForbiddenBootstrap.class, java.util.List.of()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void databaseOptInDoesNotAuthorizeExternalServices() {
        var guard = new DatabaseTestContextGuard(() -> "true",
                name -> { throw new AssertionError("external gate must precede environment access"); },
                (url, user, password) -> { throw new AssertionError("must not connect"); });
        assertThat(System.getProperty("specagent.external-tests.enabled")).isEqualTo("false");
        for (Class<?> entry : java.util.List.of(ExternalBootstrap.class, ComposedExternalBootstrap.class))
            assertThatThrownBy(() -> guard.createContextCustomizer(entry, java.util.List.of()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("-PexternalServices=true");
    }

    @Test void explicitOptInAndVerifiedMockIdentityProducePinnedTarget() throws Exception {
        var connection = org.mockito.Mockito.mock(java.sql.Connection.class);
        var statement = org.mockito.Mockito.mock(java.sql.Statement.class);
        var rows = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        org.mockito.Mockito.when(connection.createStatement()).thenReturn(statement);
        org.mockito.Mockito.when(statement.executeQuery(org.mockito.ArgumentMatchers.anyString())).thenReturn(rows);
        org.mockito.Mockito.when(rows.next()).thenReturn(true);
        org.mockito.Mockito.when(rows.getString("db")).thenReturn("spec_agent_p3b_live_offline");
        org.mockito.Mockito.when(rows.getString("usr")).thenReturn("dummy-user");
        org.mockito.Mockito.when(rows.getString("host")).thenReturn("127.0.0.1");
        org.mockito.Mockito.when(rows.getInt("port")).thenReturn(5544);
        var settings = Map.of("SPEC_AGENT_TEST_DB_URL", "jdbc:postgresql://unused/spec_agent_p3b_live_offline",
                "SPEC_AGENT_TEST_DB_USERNAME", "dummy-user", "SPEC_AGENT_TEST_DB_PASSWORD", "dummy-password",
                "SPEC_AGENT_TEST_DB_SERVER_ADDRESS", "127.0.0.1", "SPEC_AGENT_TEST_DB_SERVER_PORT", "5544");
        var guard = new DatabaseTestContextGuard(() -> "true", settings::get,
                (url, user, password) -> connection);
        var target = guard.createContextCustomizer(ForbiddenBootstrap.class, java.util.List.of());
        assertThat(target).isInstanceOf(DatabaseTestContextGuard.VerifiedTarget.class);
        var order = org.mockito.Mockito.inOrder(connection, statement);
        order.verify(connection).setReadOnly(true);
        order.verify(connection).createStatement();
        order.verify(statement).setQueryTimeout(10);
        order.verify(statement).executeQuery(org.mockito.ArgumentMatchers.anyString());
        // Authorization is demonstrated using mocks; this test creates no real connection/context.
        assertThat(STARTS.get()).isZero();
    }

    @Test void datasourceAndIndependentFlywayDriftFailBeforeApplicationBeans() {
        for (String property : java.util.List.of("spring.datasource.url", "spring.datasource.username",
                "spring.datasource.password", "spring.flyway.url", "spring.flyway.user", "spring.flyway.password")) {
            var initialized = new AtomicInteger();
            try (var context = new GenericApplicationContext()) {
                var target = new DatabaseTestContextGuard.VerifiedTarget("unused", "dummy-user", "dummy-password");
                assertThat(target.toString()).doesNotContain("dummy-password");
                target.customizeContext(context, null);
                context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("drift", Map.of(property, "other")));
                context.registerBean("sideEffect", Object.class, () -> {
                    initialized.incrementAndGet();
                    return new Object();
                });
                assertThatThrownBy(context::refresh).hasMessageContaining("target drift");
                assertThat(initialized.get()).isZero();
            }
        }
    }

    @Test void matchingMockTargetAllowsOnlyExplicitLocalBeans() {
        try (var context = new GenericApplicationContext()) {
            new DatabaseTestContextGuard.VerifiedTarget("unused", "dummy-user", "dummy-password")
                    .customizeContext(context, null);
            context.registerBean("local", Object.class, Object::new);
            context.refresh();
            assertThat(context.containsBean("local")).isTrue();
        }
    }
}

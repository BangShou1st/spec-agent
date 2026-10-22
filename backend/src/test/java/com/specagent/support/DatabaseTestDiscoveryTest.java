package com.specagent.support;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestContextManager;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.*;

/** Requires the complete eval test classpath; does not execute integration test methods. */
class DatabaseTestDiscoveryTest {
    @Test void c03RuleAndIntegrationDiscoveryStaySeparate() throws Exception {
        var loader = getClass().getClassLoader();
        Class<?> rules = Class.forName("com.specagent.eval.P3BC03NaturalLanguageScenarioTest", false, loader);
        Class<?> database = Class.forName(
                "com.specagent.eval.P3BC03NaturalLanguageScenarioDatabaseIntegrationTest", false, loader);
        assertThat(rules.getSuperclass()).isEqualTo(Object.class);
        assertThat(rules.getInterfaces()).isEmpty();
        assertThat(rules.getAnnotations()).isEmpty();
        assertThat(Arrays.stream(rules.getDeclaredMethods()).filter(m -> m.isAnnotationPresent(Test.class)).count())
                .isEqualTo(6);
        assertThat(database.getSuperclass().getName()).isEqualTo("com.specagent.eval.EvalHarnessBase");
        assertThat(database.getAnnotation(org.junit.jupiter.api.Tag.class).value()).isEqualTo("database-integration");
        // Bootstrap metadata only: no integration test instance or test method is executed.
        assertThatThrownBy(() -> new TestContextManager(database))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("explicit -PdatabaseIntegration=true");
    }

}

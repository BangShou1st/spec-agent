package com.specagent.architecture;

import archfixture.SampleCreateProjectRequest;
import archfixture.SampleLeakyResponse;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Effectiveness proof for the HTTP boundary gates: synthetic fixtures
 * (package {@code archfixture}, deliberately OUTSIDE com.specagent so the
 * production rules never see them) demonstrate that both rules actually
 * reject the violations they are supposed to catch, and that the
 * orchestration role is exempt where intended.
 *
 * <p>The fixtures import real production model types so the model-internals
 * rule is exercised against the true package predicate.
 */
class HttpBoundaryGateFixtureTest {

    private static final JavaClasses FIXTURES =
            new ClassFileImporter().importPackages("archfixture");

    @Test
    void coreDependenceOnHttpOnlyDtosIsRejected() {
        AssertionError violation = catchThrowableOfType(
                () -> ArchitectureTests.coreMustNotDependOnHttpOnlyDtos(FIXTURES).check(FIXTURES),
                AssertionError.class);

        assertThat(violation).as("core -> HTTP DTO must be rejected").isNotNull();
        assertThat(violation.getMessage())
                .contains("SampleCoreService")
                .contains("SampleCreateProjectRequest");
        // the orchestration role must NOT be flagged: it may reference HTTP DTOs
        assertThat(violation.getMessage())
                .as("orchestration role is exempt from the core rule")
                .doesNotContain("SampleOrchestrationCommandService");
    }

    @Test
    void httpDtoCarryingModelInternalsIsRejected() {
        AssertionError violation = catchThrowableOfType(
                () -> ArchitectureTests.httpSurfaceMustNotReferenceModelInternals(FIXTURES).check(FIXTURES),
                AssertionError.class);

        assertThat(violation).as("HTTP DTO -> model internals must be rejected").isNotNull();
        assertThat(violation.getMessage())
                .contains("SampleLeakyResponse")
                .contains("ModelOutputContract");
        // the plain controller must NOT be flagged: it only references its own DTOs
        assertThat(violation.getMessage())
                .as("the controller itself stays clean")
                .doesNotContain("SampleProjectController.java:");
    }

    @Test
    void fixtureSetupCoversBothRules() {
        // Guard the fixtures themselves: the DTO role must have picked up both
        // Request and Response fixtures via the controller reference, otherwise
        // the rejection tests above would pass vacuously.
        JavaClasses classes = FIXTURES;
        assertThat(ArchitectureTests.httpOnlyDtoRole(classes).test(
                classes.get(SampleCreateProjectRequest.class))).isTrue();
        assertThat(ArchitectureTests.httpOnlyDtoRole(classes).test(
                classes.get(SampleLeakyResponse.class))).isTrue();
    }
}

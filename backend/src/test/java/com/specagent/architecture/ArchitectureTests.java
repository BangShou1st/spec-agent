package com.specagent.architecture;

import com.specagent.common.PreciseConflictException;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

class ArchitectureTests {

    private static final JavaClasses CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.specagent");

    @Test
    void runtimePackagesShouldNotDependOnModelPackages() {
        ArchRule rule = noClasses()
            .that().resideInAnyPackage("com.specagent.project..", "com.specagent.route..",
                "com.specagent.node..", "com.specagent.answer..", "com.specagent.context..",
                "com.specagent.patch..", "com.specagent.spec..", "com.specagent.profile..",
                "com.specagent.common..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.model..", "com.specagent.agent..")
            .because("Runtime Kernel must not depend on Model Gateway or Agent Reasoning Layer");

        rule.check(CLASSES);
    }

    @Test
    void contextBuilderShouldNotDependOnModelGateway() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.context..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.model..", "com.specagent.agent..")
            .because("ContextBuilder must not call LLM or depend on model gateway")
            .allowEmptyShould(true);

        rule.check(CLASSES);
    }

    @Test
    void productionAgentOrchestrationHasNoFakeTypes() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.specagent.agent..")
                .should().haveSimpleNameStartingWith("Fake")
                .because("The production agent surface must not contain Fake test doubles");

        rule.check(CLASSES);
    }

    @Test
    void fakeTypesMustStayOutOfTheProductionAgentSurface() throws IOException {
        Path agentRoot = Path.of("src/main/java/com/specagent/agent");
        try (var paths = Files.walk(agentRoot)) {
            paths.filter(Files::isRegularFile).forEach(path ->
                    org.assertj.core.api.Assertions.assertThat(path.getFileName().toString())
                            .as("Fake test doubles must not be placed in the production agent package: %s", path)
                            .doesNotStartWith("Fake"));
        }
    }

    @Test
    void productConfigDefaultsToOpenCode() throws IOException {
        Path config = Path.of("src/main/resources/application.yml");
        String text = Files.readString(config);
        org.assertj.core.api.Assertions.assertThat(text)
                .contains("gateway: ${SPEC_AGENT_MODEL_GATEWAY:opencode}")
                .doesNotContain("matchIfMissing: true")
                .doesNotContain("gateway: fake");
    }

    @Test
    void testProfileUsesDedicatedDatabase() throws IOException {
        String testUrl = "jdbc:postgresql://localhost:5434/spec_agent_test";

        String productConfig = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(productConfig)
                .contains("SPEC_AGENT_DB_NAME:spec_agent")
                .doesNotContain("SPEC_AGENT_DB_NAME:spec_agent_test");

        for (Path testConfig : List.of(
                Path.of("src/main/resources/application-test.yml"),
                Path.of("src/test/resources/application-test.yml"))) {
            String text = Files.readString(testConfig);
            assertThat(text)
                    .as("test profile must use an isolated database: %s", testConfig)
                    .contains("url: " + testUrl)
                    .doesNotContain("url: jdbc:postgresql://localhost:5434/spec_agent\n")
                    .doesNotContain("url: jdbc:postgresql://localhost:5434/spec_agent\r\n");
        }
    }


    @Test
    void productionSourceContainsNoKnownTestOnlyIdentifiers() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        List<String> forbidden = List.of(
                "FakeAgentOrchestrator",
                "SPEC_AGENT_CREDENTIAL_MASTER_KEY",
                "SIBLING_SENTINEL",
                "OLD_ANSWER_SENTINEL",
                "FAKE_QUESTION");
        try (var paths = Files.walk(sourceRoot)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                try {
                    String source = Files.readString(path);
                    for (String identifier : forbidden) {
                        org.assertj.core.api.Assertions.assertThat(source)
                                .as("%s must not appear in production source %s", identifier, path)
                                .doesNotContain(identifier);
                    }
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            });
        }
    }

    @Test
    void routeNodeAnswerPatchShouldNotDependOnModel() {
        ArchRule rule = noClasses()
            .that().resideInAnyPackage("com.specagent.route..", "com.specagent.node..",
                "com.specagent.answer..", "com.specagent.patch..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.model..", "com.specagent.agent..")
            .because("Route, Node, Answer, Patch services must not depend on model packages")
            .allowEmptyShould(true);

        rule.check(CLASSES);
    }

    @Test
    void noSpringAiInProductionCode() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent..")
            .should().dependOnClassesThat()
            .haveNameMatching("org\\.springframework\\.ai..")
            .because("Spring AI must not be used as default model integration in first version");

        rule.check(CLASSES);
    }

    @Test
    void noOpenAiSdkInProductionCode() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent..")
            .should().dependOnClassesThat()
            .haveNameMatching("com\\.openai..")
            .because("Provider SDKs must not leak into production code before a provider adapter exists");

        rule.check(CLASSES);
    }

    @Test
    void noLangChain4jInProductionCode() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent..")
            .should().dependOnClassesThat()
            .haveNameMatching("dev\\.langchain4j..")
            .because("LangChain4j must not be introduced as an external provider SDK");

        rule.check(CLASSES);
    }

    @Test
    void agentLayerShouldNotDependOnProviderSdks() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.agent..")
            .should().dependOnClassesThat()
            .haveNameMatching("(org\\.springframework\\.ai|com\\.openai|dev\\.langchain4j)\\..*")
            .because("The agent reasoning layer must stay provider-agnostic until a provider adapter exists");

        rule.check(CLASSES);
    }

    @Test
    void agentLayerShouldNotDependOnProviderImplementationPackages() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.agent..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.model.provider..")
            .because("Agent reasoning layer may depend on model gateway contracts, "
                    + "not provider implementation details");

        rule.check(CLASSES);
    }

    @Test
    void runtimePackagesContainNoBusinessDomainClasses() {
        ArchRule rule = noClasses()
            .that().resideInAnyPackage("com.specagent.project..", "com.specagent.route..",
                "com.specagent.node..", "com.specagent.answer..", "com.specagent.context..",
                "com.specagent.patch..", "com.specagent.spec..", "com.specagent.profile..",
                "com.specagent.common..")
            .should().haveNameMatching(
                "(?i).*(software|marketing|ecommerce|startup|student|course|sales|legal|pitch|assignment).*")
            .because("Runtime packages must not contain concrete business-domain classes");

        rule.check(CLASSES);
    }

    @Test
    void modelPackagesShouldNotDependOnRuntimeKernel() {
        ArchRule rule = noClasses()
            .that().resideInAnyPackage("com.specagent.model..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.project..", "com.specagent.route..",
                "com.specagent.node..", "com.specagent.answer..", "com.specagent.context..",
                "com.specagent.patch..", "com.specagent.spec..", "com.specagent.profile..")
            .because("Model gateway and OpenCode transport must not depend on runtime "
                    + "repositories or services; they only speak HTTP and resolve credentials");

        rule.check(CLASSES);
    }

    @Test
    void apiMustNotDependOnRepositoryClasses() {
        ArchRule rule = noClasses()
            .that().resideInAnyPackage("com.specagent.api..", "com.specagent.globalassistant.api..")
            .and().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat()
            .haveSimpleNameEndingWith("Repository")
            .because("API controllers and DTOs must go through the service boundary; "
                    + "repositories are runtime-internal");

        rule.check(CLASSES);
    }

    @Test
    void apiMustNotDependOnModelOrProviderPackages() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.api..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.model..")
            .because("API DTOs must never expose ModelRequest, ModelResponse, or provider payloads");

        rule.check(CLASSES);
    }

    @Test
    void apiMustNotDependOnContextOrCredentialPackages() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.api..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.context..", "com.specagent.connection.credentials..")
            .because("API must never expose a raw ContextSnapshot or credential material");

        rule.check(CLASSES);
    }

    @Test
    void apiMustNotIntroduceExternalModelSdks() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.api..")
            .should().dependOnClassesThat()
            .haveNameMatching("(org\\.springframework\\.ai|com\\.openai|dev\\.langchain4j)\\..*")
            .because("The API foundation must stay free of external model SDKs");

        rule.check(CLASSES);
    }

    @Test
    void runtimeKernelMustNotDependOnApi() {
        ArchRule rule = noClasses()
            .that().resideInAnyPackage("com.specagent.project..", "com.specagent.route..",
                "com.specagent.node..", "com.specagent.answer..", "com.specagent.context..",
                "com.specagent.patch..", "com.specagent.spec..", "com.specagent.profile..",
                "com.specagent.common..", "com.specagent.agent..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.specagent.api..")
            .because("Runtime Kernel must not depend on the outermost API boundary");

        rule.check(CLASSES);
    }

    @Test
    void readModelMustNotDependOnApi() {        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.readmodel..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.specagent.api..")
            .because("The read-model/application layer must not depend on the "
                    + "outermost HTTP API boundary; query failures stay "
                    + "read-model-neutral and are mapped at the API edge");

        rule.check(CLASSES);
    }

    @Test
    void applicationLayerMustNotDependOnApi() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.application..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.specagent.api..")
            .because("Use-case orchestration lives below the HTTP boundary; "
                    + "the application layer owns its own view models, so "
                    + "dependencies only flow api -> application");

        rule.check(CLASSES);
    }

    @Test
    void errorKernelMustNotDependOnApi() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.common..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.specagent.api..")
            .because("The shared error kernel (ApiException, ApiErrorResponse, "
                    + "ApiFieldError, PreciseConflictException) is consumed by the "
                    + "application and runtime layers, so it must never reach up "
                    + "into the HTTP boundary");

        rule.check(CLASSES);
    }

    @Test
    void controllersMustNotDependOnModelGateway() {
        // The internal model-inference broker endpoint is deliberately excluded:
        // it IS the model wire contract served to the Python brain.
        ArchRule rule = noClasses()
            .that().resideInAnyPackage("com.specagent.api..", "com.specagent.globalassistant.api..")
            .and().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.model.gateway..", "com.specagent.model.provider..")
            .because("Controllers must go through the orchestrator, never call the model gateway directly");

        rule.check(CLASSES);
    }

    @Test
    void controllersMustNotDependOnContextBuilder() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.api..")
            .and().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.context..")
            .because("Controllers must not build ContextSnapshots manually");

        rule.check(CLASSES);
    }

    @Test
    void graphReadModelMustNotDependOnModelProviderCredentialOrContext() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.readmodel.graph..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.model..", "com.specagent.credential..",
                "com.specagent.context..")
            .because("GraphWorkspace is a read projection, not a model/provider/context boundary");

        rule.check(CLASSES);
    }

    @Test
    void conflictExceptionsMustCarryThePreciseConflictMarker() {
        // Guard for the CommandExecution trap: that wrapper rethrows a single
        // PreciseConflictException and otherwise degrades every
        // IllegalStateException to 409/RUNTIME_CONFLICT. A new precise
        // conflict exception that forgot the marker would therefore lose its
        // stable code silently.
        //
        // Approximation (documented): ArchUnit cannot see "is actually thrown
        // through CommandExecution.execute", so the rule keys on the
        // production naming convention for precise conflicts
        // (*ConflictException / *RuleViolationException) combined with the
        // IllegalStateException supertype. The Global Assistant package is
        // excluded on purpose: GlobalAssistantVersionConflictException is an
        // internal optimistic-concurrency retry signal caught and retried
        // inside GlobalAssistantRuntime, so it never crosses the HTTP
        // boundary and must not join this family.
        DescribedPredicate<JavaClass> preciseConflictByName = JavaClass.Predicates
                .assignableTo(IllegalStateException.class)
                .and(new DescribedPredicate<>("named *ConflictException or *RuleViolationException") {
                    @Override
                    public boolean test(JavaClass javaClass) {
                        String name = javaClass.getSimpleName();
                        return name.endsWith("ConflictException")
                                || name.endsWith("RuleViolationException");
                    }
                })
                .and(new DescribedPredicate<>("outside com.specagent.globalassistant..") {
                    @Override
                    public boolean test(JavaClass javaClass) {
                        return !javaClass.getPackageName().startsWith("com.specagent.globalassistant");
                    }
                });

        ArchRule rule = classes()
                .that(preciseConflictByName)
                .should().beAssignableTo(PreciseConflictException.class)
                .because("state-conflict exceptions that carry a precise reason code must "
                        + "extend PreciseConflictException, otherwise CommandExecution "
                        + "degrades their 409 code to RUNTIME_CONFLICT");

        rule.check(CLASSES);
    }

    @Test
    void sharedDecisionExecutionCoreStaysCycleNeutral() {
        // Slice 3A: DecisionExecutionService executes an already-prepared
        // DECISION only. Cycle preparation (context building, Answer/Patch
        // persistence, post-state reconstruction) and continuation belong to
        // the callers — never to the shared core.
        ArchRule rule = noClasses()
            .that().haveSimpleName("DecisionExecutionService")
            .should().dependOnClassesThat()
            .haveSimpleNameEndingWith("ContextBuilder")
            .orShould().dependOnClassesThat()
            .haveSimpleNameEndingWith("AnswerService")
            .orShould().dependOnClassesThat()
            .haveSimpleNameEndingWith("AnswerPatchService")
            .orShould().dependOnClassesThat()
            .haveSimpleNameEndingWith("ProjectRepository")
            .orShould().dependOnClassesThat()
            .haveSimpleNameEndingWith("RouteRepository")
            .orShould().dependOnClassesThat()
            .haveSimpleNameEndingWith("ContinuationCoordinator")
            .because("The shared DECISION execution core must stay cycle-neutral: "
                + "no context building, no Answer/Patch reads, no route loading, "
                + "no trigger dispatch, no continuation");

        rule.check(CLASSES);
    }

    @Test
    void packagesAreFreeOfCycles() {
        // Package-cycle freeze. Slice granularity is the first package segment
        // below com.specagent, so a dependency from route.. to node.. and back
        // is reported as the route <-> node cycle.
        //
        // The repository currently carries 2 slice-cycle violations, the
        // concrete paths of 2 package groups:
        //   model <-> settings, connection <-> mcp
        //
        // History: the 2026-09-19 audit froze 34 violation lines covering 7
        // package groups (model <-> settings, model <-> globalassistant,
        // answer <-> graph, node <-> route, project <-> route, context <->
        // route, connection <-> mcp). The 2026-09-20 pass broke 5 of them by
        // port sinking / dead-field removal (dependency direction now flows
        // one way; see AgentTracePort, CompatibilityDecisionSemantics,
        // RouteGraphSupportPort, ProjectActiveRoutePort, ProjectRowLockPort,
        // and the RegenerateResult cleanup), shrinking the frozen baseline to
        // the 2 remaining paths.
        //
        // model <-> settings: the inference gateways read settings services
        // (legitimate read direction), while settings depends on the
        // model.provider protocol library (adapters, catalogs, probe).
        // Breaking it needs the provider protocol library extracted into a
        // neutral package — a wide, behaviour-sensitive move, deferred.
        //
        // connection <-> mcp: ConnectionLifecycleService drives McpDiscovery
        // (legitimate direction), while the MCP runtime reads connection
        // persistence (ConnectionRepository, McpDiscoveryCacheRepository,
        // SecretStore). Breaking it needs a connection-store port plus moving
        // the MCP discovery cache out of connection.persistence — deferred.
        //
        // The freeze guarantees neither can silently get worse: this rule
        // fails on any NEW package cycle.
        //
        // ARCHUNIT STORE: violations live in backend/archunit_store. That
        // archive is committed on purpose. When a cycle is actually removed
        // (dependency inverted, port introduced, ...) the store must be
        // regenerated (rerun this test, inspect the archunit_store diff, commit
        // the shrunken store) so the rule starts guarding the new baseline.
        ArchRule rule = FreezingArchRule.freeze(slices()
                .matching("com.specagent.(*)..")
                .should().beFreeOfCycles()
                .because("package-level cycles must be broken explicitly; the 7 "
                        + "historical cycles are frozen in archunit_store and any "
                        + "newly introduced cycle fails this rule"));

        rule.check(CLASSES);
    }
}

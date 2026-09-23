package com.specagent.architecture;

import com.specagent.common.PreciseConflictException;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Structure gates after the 2026-09 business-module consolidation
 * ({@code docs/BACKEND_STRUCTURE.md}).
 *
 * <p>Layer mapping from the previous structure:
 * <ul>
 *   <li>{@code api/application/readmodel} dissolved — controllers, use-case
 *       services and query projections now live inside the business module
 *       they serve (workspace.*, agent.api, modelsettings, ...); the shared
 *       HTTP error-mapping edge is {@code com.specagent.web}.</li>
 *   <li>{@code globalassistant} → {@code assistant}; {@code settings} →
 *       {@code modelsettings}; {@code agent.contract} → {@code agent.protocol}.</li>
 *   <li>The "runtime kernel" (project/route/node/answer/context/patch/spec/
 *       profile) is now {@code com.specagent.workspace..}.</li>
 * </ul>
 */
class ArchitectureTests {

    private static final JavaClasses CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.specagent");

    private static final String[] RUNTIME_KERNEL = {
        "com.specagent.workspace..", "com.specagent.common.."};

    // ---------------------------------------------------------------- layering

    @Test
    void runtimePackagesShouldNotDependOnModelPackages() {
        ArchRule rule = noClasses()
            .that().resideInAnyPackage(RUNTIME_KERNEL)
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.model..", "com.specagent.agent..")
            .because("Runtime Kernel (workspace + common) must not depend on "
                + "Model Gateway or Agent Reasoning Layer");

        rule.check(CLASSES);
    }

    @Test
    void contextBuilderShouldNotDependOnModelGateway() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.workspace.context..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.model..", "com.specagent.agent..")
            .because("ContextBuilder must not call LLM or depend on model gateway")
            .allowEmptyShould(true);

        rule.check(CLASSES);
    }

    @Test
    void routeNodeAnswerPatchShouldNotDependOnModel() {
        ArchRule rule = noClasses()
            .that().resideInAnyPackage("com.specagent.workspace.route..",
                "com.specagent.workspace.node..", "com.specagent.workspace.answer..",
                "com.specagent.workspace.patch..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.model..", "com.specagent.agent..")
            .because("Route, Node, Answer, Patch services must not depend on model packages")
            .allowEmptyShould(true);

        rule.check(CLASSES);
    }

    @Test
    void modelPackagesShouldNotDependOnRuntimeKernel() {
        ArchRule rule = noClasses()
            .that().resideInAnyPackage("com.specagent.model..", "com.specagent.modelsettings..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.workspace..")
            .because("Model gateway, provider transport and model settings must not "
                + "depend on runtime repositories or services; they only speak HTTP "
                + "and resolve provider configuration");

        rule.check(CLASSES);
    }

    @Test
    void theWebEdgeIsATopMostLeafNothingDependsOnIt() {
        ArchRule rule = noClasses()
            .that().resideOutsideOfPackage("com.specagent.web..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.specagent.web..")
            .because("The shared HTTP error-mapping edge (ApiExceptionHandler, "
                + "GatewayErrorAdvice) sits above every business module; "
                + "no module may reach into it");

        rule.check(CLASSES);
    }

    // ------------------------------------------------------- agent boundaries

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

    // ------------------------------------------------------- HTTP controller rules
    // Controllers now live inside their business modules; the rules are keyed
    // on the Controller type name instead of a dissolved api.. package. The
    // internal model-inference broker endpoint is deliberately excluded: it IS
    // the model wire contract served to the Python brain.

    private static final DescribedPredicate<JavaClass> HTTP_CONTROLLERS = new DescribedPredicate<>(
            "HTTP controllers except the internal inference broker") {
        @Override
        public boolean test(JavaClass clazz) {
            String name = clazz.getSimpleName();
            return name.endsWith("Controller") && !name.equals("InternalModelInferenceController");
        }
    };

    @Test
    void controllersMustNotDependOnRepositoryClasses() {
        ArchRule rule = noClasses()
            .that(HTTP_CONTROLLERS)
            .should().dependOnClassesThat()
            .haveSimpleNameEndingWith("Repository")
            .because("API controllers and DTOs must go through the service boundary; "
                    + "repositories are runtime-internal");

        rule.check(CLASSES);
    }

    @Test
    void controllersMustNotDependOnModelOrProviderPackages() {
        ArchRule rule = noClasses()
            .that(HTTP_CONTROLLERS)
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.model..")
            .because("Controllers must never expose ModelRequest, ModelResponse, or provider payloads; "
                    + "model-settings controllers go through their own settings services");

        rule.check(CLASSES);
    }

    @Test
    void controllersMustNotDependOnContextOrCredentialPackages() {
        ArchRule rule = noClasses()
            .that(HTTP_CONTROLLERS)
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.workspace.context..",
                "com.specagent.connection.credentials..")
            .because("Controllers must not build ContextSnapshots manually or touch credential material");

        rule.check(CLASSES);
    }

    @Test
    void controllersMustNotIntroduceExternalModelSdks() {
        ArchRule rule = noClasses()
            .that(HTTP_CONTROLLERS)
            .should().dependOnClassesThat()
            .haveNameMatching("(org\\.springframework\\.ai|com\\.openai|dev\\.langchain4j)\\..*")
            .because("The API surface must stay free of external model SDKs");

        rule.check(CLASSES);
    }

    // ------------------------------------------------------------ misc guards

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
    void runtimePackagesContainNoBusinessDomainClasses() {
        ArchRule rule = noClasses()
            .that().resideInAnyPackage(RUNTIME_KERNEL)
            .should().haveNameMatching(
                "(?i).*(software|marketing|ecommerce|startup|student|course|sales|legal|pitch|assignment).*")
            .because("Runtime packages must not contain concrete business-domain classes");

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
    void graphCommandsMustNotDependOnModelOrAgentBrains() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.workspace.graph..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.model..", "com.specagent.agent.decision..",
                "com.specagent.agent.broker..", "com.specagent.model.provider..")
            .because("Graph commands are deterministic runtime mutations; they never call models or brains");

        rule.check(CLASSES);
    }

    @Test
    void graphCommandPackageCannotCallModelGateways() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.workspace.graph..")
            .should().dependOnClassesThat()
            .haveSimpleNameEndingWith("Gateway")
            .because("Undo/redo compensation and graph commands must stay provider-free");

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
        // IllegalStateException supertype. The Assistant module is excluded
        // on purpose: GlobalAssistantVersionConflictException is an internal
        // optimistic-concurrency retry signal caught and retried inside
        // GlobalAssistantRuntime, so it never crosses the HTTP boundary and
        // must not join this family.
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
                .and(new DescribedPredicate<>("outside com.specagent.assistant..") {
                    @Override
                    public boolean test(JavaClass javaClass) {
                        return !javaClass.getPackageName().startsWith("com.specagent.assistant");
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

    // ------------------------------------------------------------ cycle gates

    @Test
    void packagesAreFreeOfCycles() {
        // Zero-cycle invariant at the module level (first package segment).
        //
        // History: the 2026-09-19 audit froze 34 violation lines covering 7
        // package groups; all were broken by port sinking / type re-homing
        // (issue #14 and the 2026-09 structure refactor). There is no frozen
        // baseline: any module-level cycle fails the build outright.
        ArchRule rule = slices()
                .matching("com.specagent.(*)..")
                .should().beFreeOfCycles()
                .because("top-level module slices must stay acyclic; no frozen "
                        + "baseline exists, so any cycle fails this rule");

        rule.check(CLASSES);
    }

    /**
     * Workspace sub-slices. project and route are merged into one aggregate
     * slice on purpose: they are genuinely mutually dependent (the project
     * owns its active-route state, route commands validate project
     * ownership). That coupling predates the consolidation — the former
     * api/application/readmodel layering merely distributed it across
     * slices. Everything else inside workspace must stay acyclic: answer,
     * node, patch, context, spec, graph and profile may not re-introduce
     * hidden loops.
     */
    private static final SliceAssignment WORKSPACE_SLICES = new SliceAssignment() {
        @Override
        public SliceIdentifier getIdentifierOf(JavaClass clazz) {
            String pkg = clazz.getPackageName();
            if (!pkg.startsWith("com.specagent.workspace")) {
                return SliceIdentifier.ignore();
            }
            String rest = pkg.equals("com.specagent.workspace")
                    ? ""
                    : pkg.substring("com.specagent.workspace.".length());
            if (rest.startsWith("project") || rest.startsWith("route")) {
                return SliceIdentifier.of("workspace.project+route");
            }
            int dot = rest.indexOf('.');
            return SliceIdentifier.of("workspace." + (dot < 0 ? rest : rest.substring(0, dot)));
        }

        @Override
        public String getDescription() {
            return "workspace sub-slices (project and route merged as one aggregate slice)";
        }
    };

    @Test
    void workspaceSubPackagesAreFreeOfCycles() {
        ArchRule rule = SlicesRuleDefinition.slices()
                .assignedFrom(WORKSPACE_SLICES)
                .should().beFreeOfCycles()
                .because("workspace is a navigation group, not a free-for-all module; "
                        + "only the documented project<->route aggregate coupling is "
                        + "tolerated (merged into one slice), any other loop fails");

        rule.check(CLASSES);
    }

    private static final List<String> SUB_MODULE_SLICE_PATTERNS = List.of(
            "com.specagent.agent.(*)..",
            "com.specagent.assistant.(*)..",
            "com.specagent.model.(*)..",
            "com.specagent.modelsettings.(*)..",
            "com.specagent.retrieval.(*)..",
            "com.specagent.skill.(*)..",
            "com.specagent.mcp.(*)..",
            "com.specagent.connection.(*)..");

    @Test
    void subModulePackagesAreFreeOfCycles() {
        // agent, assistant, model, modelsettings, retrieval, skill, mcp and
        // connection each keep meaningful internal packages; none of them may
        // hide internal loops just because the module boundary itself is clean.
        for (String pattern : SUB_MODULE_SLICE_PATTERNS) {
            ArchRule rule = slices()
                    .matching(pattern)
                    .should().beFreeOfCycles();
            rule = rule.allowEmptyShould(true);
            rule.check(CLASSES);
        }
    }

    @Test
    void sliceRulesMatchRealPackages() {
        // Guard against silently-empty slice patterns: every pattern above
        // must resolve to at least three distinct packages, otherwise the
        // cycle rules would pass vacuously.
        for (String pattern : List.of(
                "com.specagent.(*)..",
                "com.specagent.agent.(*)..",
                "com.specagent.assistant.(*)..",
                "com.specagent.model.(*)..",
                "com.specagent.retrieval.(*)..",
                "com.specagent.skill.(*)..",
                "com.specagent.mcp.(*)..")) {
            assertThat(distinctPackagesMatching(pattern))
                    .as("slice pattern %s must match real packages", pattern)
                    .isGreaterThanOrEqualTo(2);
        }
        // modelsettings is deliberately flat (one package, no sub-slices) and
        // connection keeps only its credentials sub-slice; assert both roots
        // still hold classes so the flat layouts are not silently broken.
        assertThat(CLASSES)
                .extracting(JavaClass::getPackageName)
                .anyMatch(p -> p.equals("com.specagent.modelsettings"))
                .anyMatch(p -> p.equals("com.specagent.connection"))
                .anyMatch(p -> p.equals("com.specagent.connection.credentials"));
    }

    private long distinctPackagesMatching(String archUnitPattern) {
        // patterns have the fixed shape "<base>(*).."
        String base = archUnitPattern.substring(0, archUnitPattern.indexOf("(*)"));
        String regex = java.util.regex.Pattern.quote(base) + "[^.]+(\\..*)?";
        return CLASSES.stream()
                .map(JavaClass::getPackageName)
                .distinct()
                .filter(p -> p.matches(regex))
                .count();
    }
}

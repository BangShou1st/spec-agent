package com.specagent.architecture;

import com.specagent.common.PreciseConflictException;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaType;
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
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    // ------------------------------------------------------- HTTP boundary rules
    // Controllers now live inside their business modules; the rules are keyed
    // on type roles (Controller / Request / Response / View / Dto naming)
    // instead of a dissolved api.. package. The internal model-inference
    // broker endpoint is deliberately excluded: it IS the model wire contract
    // served to the Python brain.

    private static final DescribedPredicate<JavaClass> HTTP_CONTROLLERS = new DescribedPredicate<>(
            "HTTP controllers except the internal inference broker") {
        @Override
        public boolean test(JavaClass clazz) {
            String name = clazz.getSimpleName();
            return name.endsWith("Controller") && !name.equals("InternalModelInferenceController");
        }
    };

    /**
     * The HTTP surface: controllers plus the request/response/view DTOs they
     * hand across the boundary. These types must never leak internal runtime
     * material (a raw ContextSnapshot, credential stores) even when the
     * controller itself only references the DTO — this closes the gap a
     * controller-only check leaves open.
     */
    private static final DescribedPredicate<JavaClass> HTTP_SURFACE_TYPES =
            new DescribedPredicate<>("HTTP surface types (Controller/Request/Response/View/Dto)") {
        @Override
        public boolean test(JavaClass clazz) {
            String name = clazz.getSimpleName();
            return name.endsWith("Controller") || name.endsWith("Request")
                    || name.endsWith("Response") || name.endsWith("View")
                    || name.endsWith("Dto");
        }
    };

    /**
     * Application orchestration role across all modules: use-case services,
     * query services and command execution helpers. These roles sit between
     * the HTTP surface and the core domain, so they may touch HTTP DTOs even
     * though the core domain may not. Membership is name-role based and
     * documented here; it is deliberately narrow (do NOT treat every
     * application view as HTTP DTO, and do NOT widen the orchestration role
     * to sneak dependencies past the gate).
     */
    static boolean isApplicationOrchestrationRole(JavaClass clazz) {
        if (clazz.getSimpleName().equals("package-info")) {
            return false;
        }
        String name = clazz.getSimpleName();
        return name.equals("CommandExecution")
                || name.endsWith("Controller")
                || name.endsWith("CommandService")
                || name.endsWith("QueryService");
    }

    /**
     * Payload reachability starts at mapped endpoint signatures, not controller
     * implementation dependencies. Follow instance fields (including record
     * components), bean/JSON getters and generic type arguments, retaining
     * arrays, inherited payloads and nested classes. Never traverse service
     * calls, constructors or arbitrary helper methods. External containers
     * contribute their type arguments, but their own implementation is opaque.
     *
     * This is a conservative static type boundary, not a Jackson runtime schema:
     * instance fields are checked even when a serializer might omit them.
     * Object/JsonNode contents and dynamically populated SSE payloads require
     * their existing behavioural/contract tests.
     */
    private static Set<JavaClass> httpPayloadTypes(JavaClasses classes) {
        Set<JavaClass> known = new HashSet<>();
        classes.forEach(known::add);
        ArrayDeque<JavaClass> pending = new ArrayDeque<>();
        for (JavaClass controller : classes) {
            if (!HTTP_CONTROLLERS.test(controller)) continue;
            for (JavaMethod method : controller.getAllMethods()) {
                if (!method.isAnnotatedWith("org.springframework.web.bind.annotation.RequestMapping")
                        && !method.isMetaAnnotatedWith("org.springframework.web.bind.annotation.RequestMapping")) {
                    continue;
                }
                enqueuePayloadTypes(method.getReturnType(), pending);
                method.getParameterTypes().forEach(type -> enqueuePayloadTypes(type, pending));
            }
        }

        Set<JavaClass> payloads = new HashSet<>();
        while (!pending.isEmpty()) {
            JavaClass payload = pending.removeFirst();
            if (!known.contains(payload) || !payloads.add(payload)) continue;
            // Include arguments bound by a generic superclass, not just its erased fields.
            payload.getSuperclass().ifPresent(type -> enqueuePayloadTypes(type, pending));
            payload.getAllFields().stream()
                    .filter(field -> !field.getModifiers().contains(JavaModifier.STATIC))
                    .filter(field -> !field.getName().startsWith("this$"))
                    .forEach(field -> enqueuePayloadTypes(field.getType(), pending));
            payload.getAllMethods().stream()
                    .filter(ArchitectureTests::isPayloadGetter)
                    .forEach(method -> enqueuePayloadTypes(method.getReturnType(), pending));
        }
        return Set.copyOf(payloads);
    }

    private static void enqueuePayloadTypes(JavaType type, ArrayDeque<JavaClass> pending) {
        for (JavaClass raw : type.getAllInvolvedRawTypes()) {
            pending.addLast(raw.isArray() ? raw.getBaseComponentType() : raw);
        }
    }

    private static boolean isPayloadGetter(JavaMethod method) {
        if (!method.getParameterTypes().isEmpty()
                || method.getModifiers().contains(JavaModifier.STATIC)
                || method.getRawReturnType().getName().equals("void")) return false;
        String name = method.getName();
        boolean beanGetter = method.getModifiers().contains(JavaModifier.PUBLIC)
                && !name.equals("getClass")
                && ((name.startsWith("get") && name.length() > 3)
                    || (name.startsWith("is") && name.length() > 2
                        && (method.getRawReturnType().getName().equals("boolean")
                            || method.getRawReturnType().getName().equals("java.lang.Boolean"))));
        return beanGetter || method.isAnnotatedWith("com.fasterxml.jackson.annotation.JsonGetter")
                || method.isAnnotatedWith("com.fasterxml.jackson.annotation.JsonProperty");
    }

    /** HTTP-only naming role within the reachable payload graph; views remain shared read models. */
    static DescribedPredicate<JavaClass> httpOnlyDtoRole(JavaClasses classes) {
        Set<JavaClass> payloads = httpPayloadTypes(classes);
        return new DescribedPredicate<>("HTTP-only Request/Response/Dto types reachable from endpoints") {
            @Override
            public boolean test(JavaClass clazz) {
                String name = clazz.getSimpleName();
                return payloads.contains(clazz)
                        && (name.endsWith("Request") || name.endsWith("Response") || name.endsWith("Dto"));
            }
        };
    }

    /**
     * Rule: the core domain must never depend back on HTTP-only DTOs. Source
     * side excludes the HTTP layer itself (controllers, advices, classes in
     * the {@code <module>.api} subpackages, DTO-shaped classes — payloads
     * compose other payloads) and the application orchestration roles.
     */
    static ArchRule coreMustNotDependOnHttpOnlyDtos(JavaClasses classes) {
        DescribedPredicate<JavaClass> httpDtos = httpOnlyDtoRole(classes);
        return noClasses()
            .that().haveSimpleNameNotEndingWith("Controller")
            .and().haveSimpleNameNotEndingWith("CommandService")
            .and().haveSimpleNameNotEndingWith("QueryService")
            .and().haveSimpleNameNotEndingWith("Request")
            .and().haveSimpleNameNotEndingWith("Response")
            .and().haveSimpleNameNotEndingWith("Dto")
            .and().haveNameNotMatching("(.*\\$.*|.*CommandExecution)")
            .and().haveNameNotMatching(".*\\.api\\..*")
            .and().areNotAnnotatedWith("org.springframework.web.bind.annotation.RestControllerAdvice")
            .should().dependOnClassesThat(httpDtos)
            .because("Core domain and services must never depend back on HTTP-only "
                + "DTOs; request/response payloads belong to the HTTP layer and "
                + "the dependency direction is controller -> service -> domain");
    }

    @Test
    void coreMustNotDependOnHttpOnlyDtosInProduction() {
        ArchRule rule = coreMustNotDependOnHttpOnlyDtos(CLASSES);
        rule.check(CLASSES);
    }

    /**
     * Rule: the HTTP surface (controllers and reachable payloads) must never
     * reference model internals — a response DTO carrying a provider type
     * would leak the model seam onto the wire just as much as a controller
     * calling a gateway directly.
     */
    static ArchRule httpSurfaceMustNotReferenceModelInternals(JavaClasses classes) {
        Set<JavaClass> payloads = httpPayloadTypes(classes);
        return noClasses()
            .that(new DescribedPredicate<>("HTTP surface: controllers or reachable payload types") {
                @Override
                public boolean test(JavaClass clazz) {
                    return HTTP_CONTROLLERS.test(clazz) || payloads.contains(clazz);
                }
            })
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.model..")
            .because("Controllers and HTTP-only DTOs must never expose ModelRequest, "
                + "ModelResponse, or provider payloads; model-settings go through "
                + "their own settings services");
    }

    @Test
    void httpSurfaceMustNotReferenceModelInternalsInProduction() {
        ArchRule rule = httpSurfaceMustNotReferenceModelInternals(CLASSES);
        rule.check(CLASSES);
    }

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

    // (the model-internals constraint for controllers AND HTTP-only DTOs is
    // enforced by httpSurfaceMustNotReferenceModelInternalsInProduction above)

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

    @Test
    void httpSurfaceTypesMustNotExposeRawContextSnapshotOrCredentials() {
        // Restores the former "api must not expose a raw ContextSnapshot or
        // credential material" constraint for the dissolved api.. package:
        // request/response/view DTOs are part of the HTTP surface even when
        // the controller only references them, so the check has to cover the
        // DTOs themselves, not just the controller. Derived value types from
        // workspace.context (e.g. RequirementState) stay allowed — only the
        // raw snapshot type and credential material are forbidden.
        ArchRule rule = noClasses()
            .that(HTTP_SURFACE_TYPES)
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("com.specagent.workspace.context.ContextSnapshot")
            .orShould().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.connection.credentials..")
            .because("The HTTP surface must never expose a raw ContextSnapshot "
                + "or credential material; derived value types stay allowed");

        rule.check(CLASSES);
    }

    @Test
    void noOneReachesBackIntoHttpControllers() {
        // Restores the former "runtime kernel must not depend on api" direction:
        // with controllers living inside the business modules, the remaining
        // way to recreate the old upward edge is a service or DTO importing a
        // controller (or its DTOs) — controllers must only be referenced by
        // the framework and controller advices.
        ArchRule rule = noClasses()
            .that().haveSimpleNameNotEndingWith("Controller")
            .and().haveNameNotMatching(".*\\$.*")
            .and().areNotAnnotatedWith("org.springframework.web.bind.annotation.RestControllerAdvice")
            .should().dependOnClassesThat()
            .haveSimpleNameEndingWith("Controller")
            .because("Core services and DTOs must never depend back on HTTP "
                + "controllers; the dependency direction is controller -> service");

        rule.check(CLASSES);
    }

    @Test
    void graphWorkspaceProjectionMustNotDependOnModelContextOrCredentials() {
        // Restores the former readmodel.graph rule on the type role: the
        // GraphWorkspace* family (projection query service, views, exceptions)
        // is a read projection, not a model/provider/context boundary — even
        // though it now shares the workspace.graph package with the command
        // side.
        ArchRule rule = noClasses()
            .that().haveSimpleNameStartingWith("GraphWorkspace")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.model..",
                "com.specagent.workspace.context..",
                "com.specagent.connection.credentials..")
            .because("GraphWorkspace is a read projection, not a model/provider/context boundary");

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
     * Type roles inside workspace.route. The route module contains both core
     * domain code and application orchestration; only the orchestration role
     * may reach into workspace.project (ownership validation), which mirrors
     * the pre-consolidation layering where application.route -> project was a
     * legal one-way edge while route (domain) never imported project.
     *
     * <p>Membership is explicit and default-deny: a new route class that
     * imports project without belonging to this role will form a cycle in the
     * slice graph below and fail the gate.
     */
    private static boolean isRouteOrchestrationRole(JavaClass clazz) {
        if (!clazz.getPackageName().startsWith("com.specagent.workspace.route")) {
            return false;
        }
        if (clazz.getSimpleName().equals("package-info")) {
            return false;
        }
        String name = clazz.getSimpleName();
        return name.equals("CommandExecution")
                || name.endsWith("Controller")
                || name.endsWith("CommandService")
                || name.endsWith("QueryService");
    }

    /**
     * Workspace sub-slices with type roles. project and route are separate
     * slices; route is split by role into the orchestration surface
     * (workspace.route.app) and the core domain (workspace.route). The legal
     * edges are exactly:
     *
     * <pre>
     *   workspace.route.app -> workspace.project   (ownership validation)
     *   workspace.project   -> workspace.route     (active-route state)
     *   workspace.route.app -> workspace.route     (orchestration drives domain)
     * </pre>
     *
     * Any new edge project -> route.app or route(core) -> project closes a
     * cycle in this slice graph and fails the rule, so the former
     * project<->route aggregate exemption is replaced by precise, reviewable
     * role edges. Everything else inside workspace must stay acyclic.
     */
    private static final SliceAssignment WORKSPACE_SLICES = new SliceAssignment() {
        @Override
        public SliceIdentifier getIdentifierOf(JavaClass clazz) {
            String pkg = clazz.getPackageName();
            if (!pkg.startsWith("com.specagent.workspace")) {
                return SliceIdentifier.ignore();
            }
            if (isRouteOrchestrationRole(clazz)) {
                return SliceIdentifier.of("workspace.route.app");
            }
            String rest = pkg.equals("com.specagent.workspace")
                    ? ""
                    : pkg.substring("com.specagent.workspace.".length());
            int dot = rest.indexOf('.');
            return SliceIdentifier.of("workspace." + (dot < 0 ? rest : rest.substring(0, dot)));
        }

        @Override
        public String getDescription() {
            return "workspace sub-slices (route split into core and orchestration roles)";
        }
    };

    @Test
    void workspaceSubPackagesAreFreeOfCycles() {
        ArchRule rule = SlicesRuleDefinition.slices()
                .assignedFrom(WORKSPACE_SLICES)
                .should().beFreeOfCycles()
                .because("workspace is a navigation group, not a free-for-all module; "
                        + "route orchestration may validate project ownership and project "
                        + "may own its active-route state, but both directions are "
                        + "confined to the explicit type roles — any edge outside "
                        + "route.app -> project and project -> route(core) closes a "
                        + "cycle and fails");

        rule.check(CLASSES);
    }

    /**
     * Root-aware slice assignment for a module: classes directly in the
     * module package form the explicit {@code <module>(root)} slice, so a
     * loop between the root package and a subpackage (e.g. connection root
     * vs. connection.credentials) is detected — the plain {@code (*)..}
     * pattern would miss it because it only matches subpackages.
     */
    private static SliceAssignment moduleSlices(String basePackage) {
        String rootPrefix = basePackage + ".";
        return new SliceAssignment() {
            @Override
            public SliceIdentifier getIdentifierOf(JavaClass clazz) {
                if (clazz.getSimpleName().equals("package-info")) {
                    return SliceIdentifier.ignore();
                }
                String pkg = clazz.getPackageName();
                if (pkg.equals(basePackage)) {
                    return SliceIdentifier.of(basePackage + "(root)");
                }
                if (!pkg.startsWith(rootPrefix)) {
                    return SliceIdentifier.ignore();
                }
                String rest = pkg.substring(rootPrefix.length());
                int dot = rest.indexOf('.');
                return SliceIdentifier.of(basePackage + "." + (dot < 0 ? rest : rest.substring(0, dot)));
            }

            @Override
            public String getDescription() {
                return basePackage + " sub-slices (root package included as an explicit slice)";
            }
        };
    }

    private static final List<String> SUB_MODULE_BASES = List.of(
            "com.specagent.agent",
            "com.specagent.assistant",
            "com.specagent.model",
            "com.specagent.retrieval",
            "com.specagent.skill",
            "com.specagent.mcp",
            "com.specagent.connection");

    @Test
    void subModulePackagesAreFreeOfCycles() {
        // agent, assistant, model, retrieval, skill, mcp and connection each
        // keep meaningful internal packages; none of them may hide internal
        // loops just because the module boundary itself is clean. The
        // root-aware assignment also covers classes directly in the module
        // root package.
        for (String base : SUB_MODULE_BASES) {
            ArchRule rule = SlicesRuleDefinition.slices()
                    .assignedFrom(moduleSlices(base))
                    .should().beFreeOfCycles();
            rule = rule.allowEmptyShould(true);
            rule.check(CLASSES);
        }
    }

    @Test
    void sliceRulesMatchRealClasses() {
        // Guard against silently-empty or silently-blind slice rules: the
        // assignments above must actually produce the expected slices, and
        // each root slice must hold real classes (a root slice that silently
        // loses its classes would disable the root<->subpackage loop check).
        Map<String, List<JavaClass>> workspaceSlices = slicesOf(WORKSPACE_SLICES);
        assertThat(workspaceSlices.keySet())
                .contains("workspace.project", "workspace.route",
                        "workspace.route.app", "workspace.graph", "workspace.spec");
        // the orchestration role partition must be non-degenerate on both sides
        assertThat(workspaceSlices.get("workspace.route.app"))
                .as("route orchestration role slice")
                .isNotEmpty()
                .anyMatch(c -> c.getSimpleName().equals("RouteCommandService"))
                .anyMatch(c -> c.getSimpleName().equals("CommandExecution"));
        assertThat(workspaceSlices.get("workspace.route"))
                .as("route core slice")
                .anyMatch(c -> c.getSimpleName().equals("RouteService"));

        for (String base : SUB_MODULE_BASES) {
            Map<String, List<JavaClass>> moduleSlices = slicesOf(moduleSlices(base));
            assertThat(moduleSlices.keySet().size())
                    .as("%s must produce more than one slice", base)
                    .isGreaterThan(1);
            if (!base.equals("com.specagent.agent") && !base.equals("com.specagent.model")) {
                // these two modules keep all classes in subpackages; the rest
                // have real root-package classes covered by the (root) slice
                assertThat(moduleSlices.get(base + "(root)"))
                        .as("%s root slice must hold classes", base)
                        .isNotEmpty();
            }
        }

        // modelsettings is deliberately flat (one package, no sub-slices) and
        // connection keeps its credentials sub-slice; assert both still exist
        // so the flat layouts are not silently broken.
        assertThat(CLASSES)
                .extracting(JavaClass::getPackageName)
                .anyMatch(p -> p.equals("com.specagent.modelsettings"))
                .anyMatch(p -> p.equals("com.specagent.connection"))
                .anyMatch(p -> p.equals("com.specagent.connection.credentials"));
    }

    private Map<String, List<JavaClass>> slicesOf(SliceAssignment assignment) {
        Map<String, List<JavaClass>> result = new java.util.LinkedHashMap<>();
        for (JavaClass clazz : CLASSES) {
            SliceIdentifier id = assignment.getIdentifierOf(clazz);
            if (id == null || SliceIdentifier.ignore().equals(id)) {
                continue;
            }
            // SliceIdentifier.toString() renders as "SliceIdentifier[<name>]"
            String rendered = id.toString();
            String name = rendered.substring(rendered.indexOf('[') + 1, rendered.length() - 1);
            result.computeIfAbsent(name, k -> new java.util.ArrayList<>()).add(clazz);
        }
        return result;
    }
}

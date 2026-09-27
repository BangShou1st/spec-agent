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
 * 文件名:ArchitectureTests.java
 *
 * 测试目标:2026-09 业务模块合并后的结构门禁(见 {@code docs/BACKEND_STRUCTURE.md}),
 * 用 ArchUnit 校验分层依赖方向、HTTP 边界、无循环依赖等结构约束。
 *
 * 相对旧结构的分层映射:
 * - {@code api/application/readmodel} 已解散——控制器、用例服务和查询投影
 *       现在各归其所属业务模块(workspace.*、agent.api、modelsettings 等);
 *       共享的 HTTP 错误映射边缘是 {@code com.specagent.web}。
 * - {@code globalassistant} → {@code assistant};{@code settings} →
 *       {@code modelsettings};{@code agent.contract} → {@code agent.protocol}。
 * - "运行时内核"(project/route/node/answer/context/patch/spec/profile)
 *       现为 {@code com.specagent.workspace..}。 */
class ArchitectureTests {

    private static final JavaClasses CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.specagent");

    private static final String[] RUNTIME_KERNEL = {
        "com.specagent.workspace..", "com.specagent.common.."};

    // ---------------------------------------------------------------- 分层规则

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

    // ------------------------------------------------------- agent 边界规则

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

    // ------------------------------------------------------- HTTP 边界规则
    // 控制器现已内聚在各业务模块中;规则改为按类型角色
    // (Controller / Request / Response / View / Dto 命名)判定,
    // 而不再依赖已解散的 api.. 包。内部模型推理 broker 端点被有意排除:
    // 它本身就是提供给 Python 大脑的模型线级契约。

    private static final DescribedPredicate<JavaClass> HTTP_CONTROLLERS = new DescribedPredicate<>(
            "HTTP controllers except the internal inference broker") {
        @Override
        public boolean test(JavaClass clazz) {
            String name = clazz.getSimpleName();
            return name.endsWith("Controller") && !name.equals("InternalModelInferenceController");
        }
    };

    /**
     * HTTP 表面类型:控制器加上其跨越边界传递的请求/响应/视图 DTO。
     * 即使控制器自身只引用 DTO,这些类型也绝不能泄漏内部运行时素材
     * (原始 ContextSnapshot、凭据存储)——以此堵住只查控制器会留下的缺口。
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
     * 全模块通用的应用编排角色:用例服务、查询服务和命令执行辅助类。
     * 这些角色位于 HTTP 表面与核心领域之间,可以触碰 HTTP DTO(核心领域不行)。
     * 成员资格按命名角色判定并在此记录;范围有意收窄(不要把每个应用视图都当成
     * HTTP DTO,也不要借扩大编排角色让依赖绕过门禁)。
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
     * 载荷可达性从映射端点的签名出发,而不是控制器实现依赖。沿实例字段
     * (含 record 组件)、bean/JSON getter 和泛型实参遍历,保留数组、
     * 继承的载荷和嵌套类。绝不穿越服务调用、构造器或任意辅助方法。
     * 外部容器贡献其类型实参,但其自身实现视为不透明。
     *
     * 这是保守的静态类型边界,不是 Jackson 运行时 schema:即使序列化器
     * 可能省略字段,实例字段也一律检查。Object/JsonNode 内容和动态填充的
     * SSE 载荷由各自已有的行为/契约测试覆盖。
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
            // 包含泛型父类绑定的类型实参,而不只是擦除后的字段。
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

    /** 可达载荷图中仅限 HTTP 层的命名角色;View 仍算共享读模型,不在其列。 */
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
     * 规则:核心领域绝不能反向依赖仅限 HTTP 层的 DTO。源侧排除 HTTP 层自身
     * (控制器、advice、{@code <module>.api} 子包中的类、DTO 形态的类——
     * 载荷本就由其他载荷组合而成)以及应用编排角色。
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
     * 规则:HTTP 表面(控制器与可达载荷)绝不能引用模型内部类型——
     * 响应 DTO 携带 Provider 类型,与控制器直接调用网关一样,都会把模型
     * 接缝泄漏到线级契约上。
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

    // (控制器与仅限 HTTP 层 DTO 的模型内部类型约束
    // 由上面的 httpSurfaceMustNotReferenceModelInternalsInProduction 统一强制)

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
        // 恢复原"api 不得暴露原始 ContextSnapshot 或凭据素材"约束(针对已解散的
        // api.. 包):请求/响应/视图 DTO 属于 HTTP 表面,即使控制器只引用它们,
        // 检查也必须覆盖 DTO 本身而不只是控制器。workspace.context 的派生值类型
        // (如 RequirementState)仍然允许——只禁止原始快照类型和凭据素材。
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
        // 恢复原"运行时内核不得依赖 api"方向:控制器内聚到业务模块后,
        // 重现旧上行边的剩余途径是服务或 DTO 反向 import 控制器(或其 DTO)——
        // 控制器只能被框架和控制器 advice 引用。
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
        // 按类型角色恢复原 readmodel.graph 规则:GraphWorkspace* 家族
        // (投影查询服务、视图、异常)是读投影,不是模型/Provider/上下文边界——
        // 尽管它现在与命令侧共用 workspace.graph 包。
        ArchRule rule = noClasses()
            .that().haveSimpleNameStartingWith("GraphWorkspace")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.model..",
                "com.specagent.workspace.context..",
                "com.specagent.connection.credentials..")
            .because("GraphWorkspace is a read projection, not a model/provider/context boundary");

        rule.check(CLASSES);
    }

    // ------------------------------------------------------------ 其他守护

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
        // 防 CommandExecution 陷阱:该包装器只会原样重抛单个
        // PreciseConflictException,其余 IllegalStateException 一律降级为
        // 409/RUNTIME_CONFLICT。新的精确冲突异常若漏掉标记,就会静默丢失
        // 稳定码。
        //
        // 近似判定(已记录):ArchUnit 看不到"异常是否真的经由
        // CommandExecution.execute 抛出",因此规则改按生产代码命名约定
        // (*ConflictException / *RuleViolationException)加上
        // IllegalStateException 父类型来识别。Assistant 模块被有意排除:
        // GlobalAssistantVersionConflictException 是内部乐观并发重试信号,
        // 在 GlobalAssistantRuntime 内部捕获并重试,不跨 HTTP 边界,
        // 不应归入此家族。
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
        // Slice 3A:DecisionExecutionService 只执行已准备好的 DECISION。
        // 循环准备(上下文构建、Answer/Patch 持久化、后置状态重建)与续跑
        // 属于调用方——绝不属于共享核心。
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

    // ------------------------------------------------------------ 循环门禁

    @Test
    void packagesAreFreeOfCycles() {
        // 模块级(包名首段)零循环不变量。
        //
        // 历史:2026-09-19 审计冻结了覆盖 7 个包组的 34 条违规线;全部通过
        // 端口下沉/类型迁移消除(issue #14 与 2026-09 结构重构)。没有冻结
        // 基线:任何模块级循环都会直接导致构建失败。
        ArchRule rule = slices()
                .matching("com.specagent.(*)..")
                .should().beFreeOfCycles()
                .because("top-level module slices must stay acyclic; no frozen "
                        + "baseline exists, so any cycle fails this rule");

        rule.check(CLASSES);
    }

    /**
     * workspace.route 内部的类型角色。route 模块同时包含核心领域代码和应用编排;
     * 只有编排角色可以访问 workspace.project(所有权校验)。这对应合并前的分层:
     * application.route -> project 是合法的单向边,而 route(领域)从不 import project。
     *
     * 成员资格显式且默认拒绝:不属于此角色却 import 了 project 的 route 新类,
     * 会在下方的 slice 图中形成循环并触发门禁失败。
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
     * workspace 子 slice 及其类型角色。project 与 route 是独立 slice;route 按角色
     * 拆分为编排面(workspace.route.app)和核心领域(workspace.route)。合法的边
     * 有且仅有:
     *
     *   workspace.route.app -> workspace.project   (所有权校验)
     *   workspace.project   -> workspace.route     (active-route 状态)
     *   workspace.route.app -> workspace.route     (编排驱动领域)
     *
     * 任何新增的 project -> route.app 或 route(核心) -> project 边都会在此
     * slice 图中闭合成循环并触发规则失败。原 project<->route 聚合豁免由此被
     * 精确、可评审的角色边取代。workspace 内其余部分必须保持无循环。
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
     * 模块的根包感知 slice 划分:直接位于模块包下的类构成显式的
     * {@code <module>(root)} slice,从而能检测根包与子包之间的循环
     * (如 connection 根包与 connection.credentials)——普通的 {@code (*)..}
     * 模式只匹配子包,会漏掉这种情况。
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
        // agent、assistant、model、retrieval、skill、mcp、connection 各自都有
        // 有意义的内部包;不能因为模块边界干净就允许内部藏循环。
        // 根包感知划分同时覆盖直接位于模块根包下的类。
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
        // 防止 slice 规则静默失效(为空或失明):上面的划分必须真的产出预期
        // slice,且每个 root slice 必须持有真实类(root slice 若静默丢光类,
        // 根包<->子包循环检查就会失效)。
        Map<String, List<JavaClass>> workspaceSlices = slicesOf(WORKSPACE_SLICES);
        assertThat(workspaceSlices.keySet())
                .contains("workspace.project", "workspace.route",
                        "workspace.route.app", "workspace.graph", "workspace.spec");
        // 编排角色划分在两侧都必须非退化
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
                // 这两个模块的所有类都在子包中;其余模块根包下有真实类,
                // 由 (root) slice 覆盖
                assertThat(moduleSlices.get(base + "(root)"))
                        .as("%s root slice must hold classes", base)
                        .isNotEmpty();
            }
        }

        // modelsettings 有意保持扁平(单包、无子 slice),connection 保留其
        // credentials 子 slice;断言两者仍然存在,防止扁平布局被静默破坏。
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
            // SliceIdentifier.toString() 的输出形如 "SliceIdentifier[<name>]"
            String rendered = id.toString();
            String name = rendered.substring(rendered.indexOf('[') + 1, rendered.length() - 1);
            result.computeIfAbsent(name, k -> new java.util.ArrayList<>()).add(clazz);
        }
        return result;
    }
}

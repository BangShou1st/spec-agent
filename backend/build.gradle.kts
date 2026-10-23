plugins {
    id("org.springframework.boot") version "3.3.2"
    id("io.spring.dependency-management") version "1.1.6"
    id("java")
}

group = "com.specagent"
version = "0.0.1-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_21
java.targetCompatibility = JavaVersion.VERSION_21

repositories {
    mavenCentral()
    // Kilo Gateway 共享库通过 JitPack 公开发布（多模块坐标：com.github.User.Repo:Module:Tag）。
    // 消费端无需 Token；本地联调可用 KILO_GATEWAY_LOCAL 指向源码检出目录做 composite build 覆盖。
    maven {
        name = "jitpack"
        url = uri("https://jitpack.io")
    }
    // OpenCode Zen 共享库同样通过 JitPack 公开发布
    // (com.github.BangShou1st.opencode-zen:opencode-zen-spring-boot-starter:v1.4.2)。
    // 私有生态仓库(GitHub Packages / Nexus)已经不再发版,消费端也不需要任何 Token:
    // 曾经按环境变量注入的仓库地址与凭据因此整体移除,依赖解析只剩公开来源。
}

// Evaluation harness source set: com.specagent.eval (+ com.specagent.trace)
// compile against the production sources but are NOT part of the shipped
// application. bootRun / production classpaths never see eval classes; the
// eval* Gradle tasks and the test source set do (via the dependency below).
val eval = sourceSets.create("eval") {
    java.srcDir("src/eval/java")
}
configurations.named("evalImplementation") {
    extendsFrom(configurations.implementation.get())
}
dependencies {
    "evalImplementation"(sourceSets["main"].output)
}

dependencies {
    implementation("com.github.BangShou1st.opencode-zen:opencode-zen-spring-boot-starter:v1.4.2")
    // Kilo Gateway（第三个模型供应商）客户端 Starter。提供 KiloGatewayClient，
    // 直连 https://ai-gateway.kilo.ai/api/v1，免费模型匿名可用。
    implementation("com.github.BangShou1st.kilo-gateway:kilo-gateway-spring-boot-starter:v0.2.1")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // ---------------------------------------------------------------------
    // 第二阶段（身份/权限/凭证隔离）安全基础。
    //
    // Security 与 Session 由 com.specagent.access 独占使用：业务模块通过
    // ActorContext / AuthorizationGuard 端口接入，不直接依赖 Security 类型。
    // spring-session-jdbc 让 API 多实例共享会话（不要求粘性会话，也刻意
    // 不引入 Redis —— 只有测量证明有压力后才替换会话存储）。
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.session:spring-session-jdbc")
    // Pure-Java git client for HTTPS Skill imports. JGit never runs git
    // hooks, never initializes submodules, and never smudges LFS content
    // automatically — the import-security posture the Skill runtime requires.
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r")
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.apache:7.8.0.202609011348-r")
    // Zip parsing with Unix entry-mode visibility (symlink/device rejection).
    implementation("org.apache.commons:commons-compress:1.28.0")
    // Official MCP Java SDK (Remote Streamable HTTP transport). Version 0.18.x
// targets Jackson 2, matching Spring Boot 3.3 — the 1.x line moved to Jackson
// 3 and would conflict at runtime. All SDK types stay behind com.specagent.mcp;
// nothing leaks into agent/brain/policy.
implementation("io.modelcontextprotocol.sdk:mcp:0.18.4")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.0")
    testImplementation("org.assertj:assertj-core:3.26.3")
    // Eval harness classes (ScenarioRunner, BrainScriptInstaller, ...) are
    // consumed by the eval* Test tasks and by eval tests in src/test/java.
    testImplementation(eval.output)
}

// Deliberately small, reviewed offline set. Expand only after reviewing inheritance,
// extensions, static initialization and the entire invoked fixture/call chain.
val auditedOfflineTests = setOf(
    // D4 adapter boundary: Mockito store/JDBC/host only; no Spring bootstrap or external calls.
    "com.specagent.assistant.tool.RagD4EvidenceBoundaryTest",
    "com.specagent.assistant.api.RagD4BrokerObservationTest",
    "com.specagent.retrieval.context.RagD4MemoryEvidenceTest",
    "com.specagent.retrieval.RagD4SharedSourceValidationTest",
    "com.specagent.agent.decision.RagD4ClaimAuthorityTest",
    "com.specagent.model.provider.PublishedZenStarterCompatibilityTest",
    "com.specagent.eval.P3BLiveDatabaseGuardTest",
    "com.specagent.eval.P3BC03NaturalLanguageScenarioTest",
    "com.specagent.eval.P3BGateMeasurementTest",
    "com.specagent.support.DatabaseTestContextGuardTest",
    "com.specagent.support.DatabaseTestDiscoveryTest",
    // All provider gateways are Mockito mocks; concurrency/LRU fixtures are in-memory.
    "com.specagent.model.inference.RoutingModelInferenceGatewayTest",
    "com.specagent.model.inference.ModelTargetBindingContractTest",
    "com.specagent.agent.runtime.AgentRunModelBindingRepositoryOfflineTest",
    "com.specagent.agent.decision.AgentBrainArgumentGroundingTest",
    "com.specagent.agent.action.C16WaitContractTest",
    "com.specagent.agent.runtime.LegacyBindingMigrationGuardTest",
    "com.specagent.agent.runtime.DeferredContinuationOfflineTest",
    "com.specagent.capability.DeferredCapabilityRuntimeTest",
    "com.specagent.capability.DeferredCapabilityRepositoryTest",
    "com.specagent.capability.GuardedCapabilityOrderTest",
    "com.specagent.agent.action.ActionEligibilityWaitV4Test",
    "com.specagent.agent.runtime.WaitDependencyServiceOfflineTest",
    "com.specagent.agent.runtime.WaitDependencyRepositoryOfflineTest",
    "com.specagent.agent.runtime.WaitLifecycleOfflineTest",
    "com.specagent.agent.runtime.WaitDecisionExecutionOfflineTest",
    // Actual production readiness adapter/scanner, Mockito JDBC only and resource-free transactions.
    "com.specagent.capability.RetrievalAwaitGenerationOfflineTest",
    "com.specagent.capability.RebuildWaitCompletionRecoveryOfflineTest",
    "com.specagent.retrieval.workload.RebuildReadinessOfflineTest",
    "com.specagent.capability.RebuildWaitRepositoryOfflineTest",
    "com.specagent.retrieval.context.RetrievalContextServiceErrorClassificationTest",
    "com.specagent.retrieval.eval.RetrievalQualityScorerTest",
    "com.specagent.retrieval.eval.RetrievalAcceptanceGateValidatorTest",
    "com.specagent.retrieval.eval.RetrievalExecutionClaimManagerTest",
    "com.specagent.retrieval.eval.RetrievalEvaluationJournalTest",
    "com.specagent.retrieval.eval.RetrievalRecoveryGateTest",
    // Snapshot/audit/hash rules and temporary filesystem ledgers only; no application bootstrap.
    "com.specagent.retrieval.eval.RetrievalEvaluationSnapshotTest",
    // ArchUnit reads class files only, without initializing application classes.
    "com.specagent.architecture.AgentBoundaryArchitectureTests",
    "com.specagent.architecture.CapabilitySkillsMcpBoundaryTests"
)
val databaseIntegrationEnabled = providers.gradleProperty("databaseIntegration").map { it == "true" }.orElse(false)
val externalServicesEnabled = providers.gradleProperty("externalServices").map { it == "true" }.orElse(false)

tasks.withType<Test> {
    useJUnitPlatform()
    // 第二阶段（身份/权限）给测试 classpath 增加了 Spring Security 与
    // Spring Session，每个 @SpringBootTest 上下文都更重。Gradle 的默认测试堆
    // 在全量套件的上下文缓存下会抛 OutOfMemoryError，而失败形态是大量
    // "上下文加载失败"，看起来像大范围回归、实际是资源不足。
    // 3g 是当前套件实测可稳定的初值：只影响测试 JVM，不改动产品运行参数。
    maxHeapSize = "3g"
    // Each cached integration context owns a JDBC pool and a mode-admission connection.
    // Bound their total below the isolated PostgreSQL limit, including dual API acceptance processes.
    systemProperty("spring.test.context.cache.maxSize", "6")
    systemProperty("specagent.database-tests.enabled", databaseIntegrationEnabled.get().toString())
    systemProperty("specagent.external-tests.enabled", externalServicesEnabled.get().toString())
    if (!databaseIntegrationEnabled.get()) {
        useJUnitPlatform { excludeTags("database-integration") }
    }
    if (!externalServicesEnabled.get()) useJUnitPlatform { excludeTags("external-service") }
    if (databaseIntegrationEnabled.get() && name != "testOffline") {
        // Disabled-bootstrap assertions execute in testOffline with both gates false.
        filter {
            excludeTestsMatching("com.specagent.support.DatabaseTestContextGuardTest")
            excludeTestsMatching("com.specagent.support.DatabaseTestDiscoveryTest")
            excludeTestsMatching("com.specagent.eval.P3BLiveDatabaseGuardTest")
        }
    }
    doFirst {
        if (!databaseIntegrationEnabled.get()) {
            if (name != "testOffline") {
                throw GradleException("Unreviewed test selection: use testOffline. " +
                    "Database suites require -PdatabaseIntegration=true and isolated target; external suites also require -PexternalServices=true. --offline only controls dependencies.")
            }
        }
    }
}

tasks.register<Test>("testOffline") {
    group = "verification"
    description = "Runs only reviewed pure-rule and mock database/bootstrap guard tests; no real datasource."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { auditedOfflineTests.forEach { includeTestsMatching(it) } }
    // This task can never inherit an integration opt-in from the invoking shell/build.
    systemProperty("specagent.database-tests.enabled", "false")
    systemProperty("specagent.external-tests.enabled", "false")
    useJUnitPlatform { excludeTags("database-integration", "external-service") }
}

tasks.register<Test>("testDatabaseIntegration") {
    group = "verification"
    description = "Runs integration-named suites; requires explicit isolated target and opt-in."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { excludeTags("external-service") }
    filter {
        includeTestsMatching("*IntegrationTest")
        includeTestsMatching("*AcceptanceTest")
        includeTestsMatching("*EndToEndTest")
        excludeTestsMatching("com.specagent.eval.EvalLive*")
        excludeTestsMatching("com.specagent.agent.runtime.PythonBrainCrossLanguageIntegrationTest")
    }
    doFirst {
        if (!databaseIntegrationEnabled.get()) throw GradleException("Requires -PdatabaseIntegration=true")
    }
}

// Reject the whole graph before any task action: task combinations must not
// turn an explicitly offline invocation into bootRun, a live JavaExec or an
// integration suite. Test.doFirst above remains a second execution-time gate.
gradle.taskGraph.whenReady {
    if (!externalServicesEnabled.get() && allTasks.any { it.name.startsWith("evalLive") }) {
        throw GradleException("Live provider tasks require explicit -PexternalServices=true")
    }
    val offline = tasks.named("testOffline").get()
    if (hasTask(offline)) {
        val unsafe = allTasks.filter { it != offline && (it is Test || it is JavaExec) }
        if (unsafe.isNotEmpty()) throw GradleException(
            "testOffline cannot be combined with other Test/JavaExec tasks: " + unsafe.joinToString { it.path })
    }
    if (!databaseIntegrationEnabled.get()) {
        val unaudited = allTasks.filterIsInstance<Test>().filter { it != offline }
        if (unaudited.isNotEmpty()) throw GradleException(
            "Unreviewed test selection: use testOffline; integration requires -PdatabaseIntegration=true and isolated target")
    }
}

// The default `test` task never runs live-provider suites. Live behavior is
// recorded only through the explicit evalLive* tasks, so ordinary PR runs
// and local `./gradlew test` invocations stay deterministic and offline-safe.
// The Python brain cross-language exit gate is excluded too: it needs a real
// broker-mode agent-brain dialing back into THIS process's port, which an
// offline (or dev-brain-occupied) machine cannot satisfy — see testCrossLanguage.
tasks.named<Test>("test") {
    filter {
        excludeTestsMatching("com.specagent.eval.EvalLive*")
        excludeTestsMatching("com.specagent.agent.runtime.PythonBrainCrossLanguageIntegrationTest")
    }
}

// P2 evaluation harness (deterministic B-fast profile, CI-blocking).
// Runs the scenario contract tests, corpus validation, Layer A / B-fast
// corpus scenarios, and the baseline artifact suite — no live provider,
// no judge model, no secrets required. Live baseline suites
// (EvalLive*) are excluded: live-provider flakiness must never block PRs.
tasks.register<Test>("evalBFast") {
    group = "verification"
    description = "Runs the deterministic P2 agent evaluation harness (B-fast) only."
    useJUnitPlatform()
    filter {
        includeTestsMatching("com.specagent.eval.*")
        excludeTestsMatching("com.specagent.eval.EvalLive*")
    }
    testLogging {
        events("failed")
        showStandardStreams = true
    }
}

tasks.register<Test>("evalRetrievalFast") {
    group = "verification"
    description = "Runs the deterministic offline Memory + RAG retrieval evaluation gate."
    useJUnitPlatform { excludeTags("external-service") }
    systemProperty("spec.agent.retrieval.embedding.provider", "fake")
    filter {
        includeTestsMatching("com.specagent.retrieval.eval.*")
        includeTestsMatching("com.specagent.retrieval.RetrievalV1HardeningIntegrationTest")
    }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

tasks.register<Test>("testNonLive") {
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    group = "verification"
    description = "Runs the complete backend test suite except explicit live-provider suites and the cross-language exit gate."
    useJUnitPlatform()
    filter {
        excludeTestsMatching("com.specagent.eval.EvalLive*")
        excludeTestsMatching("com.specagent.agent.runtime.PythonBrainCrossLanguageIntegrationTest")
    }
}

// Cross-language exit gate (Java broker <-> real Python agent-brain). It is a
// separate task because it cannot be verified offline and is environment
// coupled: the brain must run in broker mode and dial back into THIS test
// process, so a dev brain already listening on 8100 answers instead and the
// assertions fail with 502s. The suite is therefore excluded from `test` and
// `testNonLive` and must be run explicitly against a dedicated brain:
//
//   1. pick a fixed rendezvous port for the test process (the class reads it):
//        set SPEC_AGENT_CROSS_LANG_PORT=8099
//   2. start a dedicated brain pointed at that port:
//        cd agent-brain
//        set SPEC_AGENT_BRAIN_MODEL_MODE=broker
//        set SPEC_AGENT_INTERNAL_BROKER_URL=http://localhost:8099/internal/v1/model-inference
//        .venv/Scripts/uvicorn spec_agent_brain.app:app --port 8100
//   3. run: backend> gradlew.bat testCrossLanguage
//
// See the test class javadoc for the full contract. When no brain is running
// the test still skips instead of failing.
tasks.register<Test>("testCrossLanguage") {
    group = "verification"
    description = "Runs the Python brain cross-language exit gate (requires a broker-mode agent-brain on localhost:8100)."
    useJUnitPlatform()
    filter {
        includeTestsMatching("com.specagent.agent.runtime.PythonBrainCrossLanguageIntegrationTest")
    }
    testLogging {
        events("failed", "skipped")
        showStandardStreams = true
    }
}

tasks.register<JavaExec>("eligibilityShadowReplay") {
    group = "verification"
    description = "Replays Runtime-owned action eligibility over existing semantic trace artifacts."
    classpath = eval.runtimeClasspath
    mainClass.set("com.specagent.eval.ActionEligibilityShadowReplay")
    val artifactPaths = providers.gradleProperty("eligibilityArtifacts")
    val outputPath = providers.gradleProperty("eligibilityOutput")
    doFirst {
        val inputs = artifactPaths.orNull
            ?: throw GradleException("-PeligibilityArtifacts=<results.jsonl;...> is required")
        val output = outputPath.orNull
            ?: throw GradleException("-PeligibilityOutput=<directory> is required")
        args = listOf(output) + inputs.split(';').filter { it.isNotBlank() }
    }
}

// Explicit provider-native GA qualification using existing host settings, read only.
// Does not boot product workers or qualify the authenticated host RPC chain.
tasks.register<JavaExec>("qualifyGaNative") {
    group = "verification"
    description = "Records native SSE tool/followup qualification of the configured host model (no retry)."
    classpath = eval.runtimeClasspath
    mainClass.set("com.specagent.eval.GaNativeQualification")
}

// Kilo Gateway per-model GA native tool-calling qualification (live, opt-in, no retry).
// Only models that pass all five checks may be registered in
// spec.global-assistant.ga-native.kilo-qualified-models (see docs/MODEL_GATEWAY.md §18).
tasks.register<JavaExec>("qualifyGaNativeKilo") {
    group = "verification"
    description = "Qualifies Kilo Gateway models for native GA tool calling (live; requires network)."
    classpath = eval.runtimeClasspath
    mainClass.set("com.specagent.eval.KiloGaNativeQualification")
    // -PkiloModels=a:free,b:free selects the candidates (defaults to the curated set).
    val kiloModels = providers.gradleProperty("kiloModels")
    doFirst {
        kiloModels.orNull?.takeIf { it.isNotBlank() }?.let { systemProperty("kilo.qualification.models", it) }
    }
}

// OpenCode Zen upstream request-contract gate: the shared-library request must be accepted
// upstream (HTTP 200 or 429). Any other status — notably 403 FreeTierError — means the request
// construction is wrong, not that the provider is refusing the product. Opt-in: it dials the
// real upstream directly (NO_PROXY), so it is never part of test / testNonLive.
tasks.register<JavaExec>("zenUpstreamContract") {
    group = "verification"
    description = "Asserts the shared OpenCode Zen request is accepted upstream (200/429 only)."
    classpath = eval.runtimeClasspath
    mainClass.set("com.specagent.eval.ZenUpstreamContractCheck")
    // -PzenModel=<model-id> selects the model (defaults to a free model).
    val zenModel = providers.gradleProperty("zenModel")
    doFirst {
        zenModel.orNull?.takeIf { it.isNotBlank() }?.let { systemProperty("zen.contract.model", it) }
    }
}

// P2 Phase 2 — Live agent behavioral baseline (explicit, non-blocking).
// Same Scenario Contract through Python Brain + live provider, N=3
// repetitions per variant. The suite requires explicit external live
// provider configuration; missing/invalid settings fail before scenarios.
// Never wire this into PR-blocking CI: it records the baseline, it does
// not gate on behavioral pass/fail.
tasks.register<Test>("evalLive") {
    group = "verification"
    description = "Records the P2 live agent behavioral baseline (non-blocking, requires live brain + provider)."
    useJUnitPlatform()
    filter {
        includeTestsMatching("com.specagent.eval.EvalLiveBaselineSuiteTest")
    }
    testLogging {
        events("failed", "skipped")
        showStandardStreams = true
    }
}

// One-attempt live smoke only; this is not the baseline and never runs as
// part of evalBFast or evalLive.
tasks.register<Test>("evalLiveSmoke") {
    group = "verification"
    description = "Runs the single E01 B-live smoke (requires explicit live provider config)."
    useJUnitPlatform()
    filter {
        includeTestsMatching("com.specagent.eval.EvalLiveE01SmokeTest")
    }
    testLogging {
        events("failed", "skipped")
        showStandardStreams = true
    }
}

// Targeted P3-B live decision evidence. Kept separate from evalBFast and the
// default test task because it requires the external provider and broker.
tasks.register<Test>("evalLiveP3B") {
    group = "verification"
    description = "Records real-model P3-B priority scenarios (requires broker + the active provider, e.g. Kilo; isolated DB via SPEC_AGENT_EVAL_DB_URL)."
    useJUnitPlatform()
    filter {
        includeTestsMatching("com.specagent.eval.EvalLiveP3BDecisionQualityTest")
    }
    testLogging {
        events("failed", "skipped")
        showStandardStreams = true
    }
}

// Phase 3B — targeted semantic evidence rerun. This suite is explicitly
// opt-in, writes to build/eval-live-diagnostic/<run>, and never overwrites the
// formal build/eval-live baseline.
tasks.register<Test>("evalLiveDiagnostic") {
    group = "verification"
    description = "Runs the targeted live semantic diagnostic suite (requires explicit live provider config)."
    useJUnitPlatform()
    filter {
        includeTestsMatching("com.specagent.eval.EvalLiveDiagnosticSuiteTest")
    }
    testLogging {
        events("failed", "skipped")
        showStandardStreams = true
    }
}

// Phase 3 — five-cycle provider qualification. This records reliability and
// protocol/schema completion only; it is not a behavioral corpus score and
// must be run separately for each candidate model selected through the
// explicit external environment.
tasks.register<Test>("evalLiveQualification") {
    group = "verification"
    description = "Qualifies the configured live reference-model candidate (5 real STATE_UPDATE -> DECISION cycles)."
    useJUnitPlatform()
    filter {
        includeTestsMatching("com.specagent.eval.EvalLiveQualificationSuiteTest")
    }
    testLogging {
        events("failed", "skipped")
        showStandardStreams = true
    }
}

// Single-scenario entry: -PevalTest=E01SimpleAnswerTest
// (omit to run the whole eval package via evalBFast).
tasks.register<Test>("evalScenario") {
    group = "verification"
    description = "Runs one P2 evaluation test class (e.g. -PevalTest=E01SimpleAnswerTest)."
    useJUnitPlatform()
    val evalTest = project.findProperty("evalTest")?.toString()?.takeIf { it.isNotBlank() }
    filter {
        if (evalTest != null) {
            includeTestsMatching("com.specagent.eval.$evalTest")
        } else {
            includeTestsMatching("com.specagent.eval.*")
        }
    }
    testLogging {
        events("failed")
        showStandardStreams = true
    }
}

// P3-A: real-model evaluation of the PROJECT Agent's understanding (B) and
// next-action decision (C) quality. Explicitly opt-in and non-blocking: it
// requires a broker-mode agent-brain plus the project's configured model, so it
// can never run in PR CI. Records build/eval-p3a/layer-bc-live.json.
tasks.register<Test>("evalP3ALiveProjectAgent") {
    group = "verification"
    description = "Records the P3-A project-Agent B/C live baseline (requires live brain + provider)."
    useJUnitPlatform()
    filter {
        includeTestsMatching("com.specagent.eval.EvalLiveP3AProjectAgentSuiteTest")
    }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

// P3-A: deterministic A-layer retrieval evaluation over the frozen Chinese
// calibration set, including a same-harness before/after comparison for the
// CJK lexical fix. Reuses production retrieval; no live provider needed.
tasks.register<Test>("evalP3ARetrieval") {
    group = "verification"
    description = "Runs the P3-A deterministic retrieval (layer A) evaluation."
    useJUnitPlatform()
    systemProperty("spec.agent.retrieval.embedding.provider", "fake")
    filter {
        includeTestsMatching("com.specagent.retrieval.eval.P3A*")
    }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}

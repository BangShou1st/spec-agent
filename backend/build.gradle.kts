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
    implementation("com.sharedlibs:opencode-zen-spring-boot-starter:1.2.0")
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
    useJUnitPlatform()
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

// OpenCode Zen upstream request-contract gate: the shared-library request must be accepted
// upstream (HTTP 200 or 429). Any other status — notably 403 FreeTierError — means the request
// construction is wrong, not that the provider is refusing the product. Opt-in: it dials the
// real upstream directly (NO_PROXY), so it is never part of test / testNonLive.
tasks.register<JavaExec>("zenUpstreamContract") {
    group = "verification"
    description = "Asserts the shared OpenCode Zen request is accepted upstream (200/429 only)."
    classpath = eval.runtimeClasspath
    mainClass.set("com.specagent.eval.ZenUpstreamContractCheck")
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

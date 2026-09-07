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

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.0")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// The default `test` task never runs live-provider suites. Live behavior is
// recorded only through the explicit evalLive* tasks, so ordinary PR runs
// and local `./gradlew test` invocations stay deterministic and offline-safe.
tasks.named<Test>("test") {
    filter {
        excludeTestsMatching("com.specagent.eval.EvalLive*")
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

tasks.register<Test>("testNonLive") {
    group = "verification"
    description = "Runs the complete backend test suite except explicit live-provider suites."
    useJUnitPlatform()
    filter {
        excludeTestsMatching("com.specagent.eval.EvalLive*")
    }
}

tasks.register<JavaExec>("eligibilityShadowReplay") {
    group = "verification"
    description = "Replays Runtime-owned action eligibility over existing semantic trace artifacts."
    classpath = sourceSets["main"].runtimeClasspath
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

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}

plugins { java }

group = "com.specagent.verification"
version = "1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories { mavenCentral() }

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("org.springframework:spring-context:6.1.11")
    implementation("org.springframework.boot:spring-boot-autoconfigure:3.3.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.springframework:spring-jdbc:6.1.11")
    testImplementation("org.springframework:spring-test:6.1.11")
    testImplementation("org.springframework.boot:spring-boot-test:3.3.2")
    testImplementation("org.springframework.boot:spring-boot-test-autoconfigure:3.3.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    // Match the Spring Boot 3.3.2 managed versions used by the backend.
    testImplementation("net.bytebuddy:byte-buddy:1.14.18")
    testImplementation("net.bytebuddy:byte-buddy-agent:1.14.18")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.3")
}

// Compile the real tracked production files in place. No copied implementations,
// generated stubs, backend source-set outputs, eval output or production classpath.
sourceSets {
    main {
        java.setSrcDirs(listOf("../src/main/java"))
        java.include("com/specagent/agent/protocol/*.java")
        java.include("com/specagent/agent/action/ActionEligibility*.java")
        java.include("com/specagent/agent/decision/AgentDecisionEngine.java")
        java.include("com/specagent/agent/decision/AgentBrainResponseValidator.java")
        java.include("com/specagent/agent/decision/DeterministicEngineFaultPlan.java")
        java.include("com/specagent/agent/decision/LocalDeterministicDecisionEngine.java")
        java.include("com/specagent/common/Hashes.java", "com/specagent/common/Json.java")
        java.include("com/specagent/retrieval/RetrievedContextItem.java")
        java.include("com/specagent/retrieval/RetrievalSourceKind.java")
        java.include("com/specagent/retrieval/RetrievalScope.java")
        java.include("com/specagent/retrieval/MemoryAuthority.java")
        java.include("com/specagent/model/contract/ModelOutputContract.java")
        java.include("com/specagent/model/contract/ModelInferenceRequest.java")
        java.include("com/specagent/model/contract/ModelInferenceMessage.java")
    }
    test {
        java.setSrcDirs(listOf("../src/test/java", "../src/eval/java"))
        java.include("com/specagent/agent/action/ActionEligibility*Test.java")
        java.include("com/specagent/model/inference/ModelOutputContractTest.java")
        // AE-4 fixtures use mocked JDBC and local contexts only. Keep full eval
        // harness/discovery classes outside this independent compilation boundary.
        java.include("com/specagent/eval/EvalLiveIsolatedDatabase.java")
        java.include("com/specagent/eval/P3BLiveDatabaseGuardTest.java")
        java.include("com/specagent/support/DatabaseTestContextGuard.java")
        java.include("com/specagent/support/DatabaseTestContextGuardTest.java")
        // Existing cleanup failure tests use only UUIDs, Consumers and JUnit.
        // They do not load EvalHarnessBase or execute its JDBC cleanup.
        java.include("com/specagent/eval/EvalProjectCleanup.java")
        java.include("com/specagent/eval/EvalCleanupFailureHandler.java")
        java.include("com/specagent/eval/EvalProjectCleanupTest.java")
        // Deterministic corpus/scoring only: never compile EvalHarnessBase.
        listOf("P3BCorpus", "P3BDecisionQuality", "P3BGateMeasurementTest",
            "P3BC03NaturalLanguageScenarios", "P3BC03NaturalLanguageScenarioTest",
            "ScenarioDefinition", "GivenSpec", "ExpectSpec", "VariantSpec", "CallBudget", "PropertyCheck",
            "BrainScript", "BrainClaim", "BrainDecision", "CapabilitySpec", "ResourceSpec",
            "GraphStep", "UserEvent", "RouteContextSpec", "FocusContextSpec").forEach {
            java.include("com/specagent/eval/$it.java")
        }
        resources.setSrcDirs(listOf("../src/test/resources"))
        resources.include("META-INF/spring.factories")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.test {
    useJUnitPlatform()
    workingDir = projectDir.parentFile
    maxHeapSize = "512m"
    systemProperty("specagent.database-tests.enabled", "false")
    systemProperty("specagent.external-tests.enabled", "false")
    testLogging { events("failed", "skipped") }
}

tasks.register("verificationScope") {
    group = "verification"
    description = "Prints the exact real-source compilation and test scope."
    doLast {
        for (name in listOf("main", "test")) {
            val files = sourceSets[name].allJava.files.sortedBy { it.path }
            println("$name: ${files.size} Java source files")
            files.forEach { println(it.relativeTo(projectDir.parentFile).invariantSeparatorsPath) }
        }
    }
}

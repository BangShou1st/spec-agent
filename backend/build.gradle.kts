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

// P2 evaluation harness (deterministic B-fast profile, CI-blocking).
// Runs the scenario contract tests, corpus validation, Layer A / B-fast
// corpus scenarios, and the baseline artifact suite — no live provider,
// no judge model, no secrets required.
tasks.register<Test>("evalBFast") {
    group = "verification"
    description = "Runs the deterministic P2 agent evaluation harness (B-fast) only."
    useJUnitPlatform()
    filter {
        includeTestsMatching("com.specagent.eval.*")
    }
    testLogging {
        events("failed")
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

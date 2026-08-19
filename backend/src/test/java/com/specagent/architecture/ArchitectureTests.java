package com.specagent.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

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
        org.assertj.core.api.Assertions.assertThat(Path.of(
                "src/main/java/com/specagent/testing/FakeModelAdapter.java")).exists();
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
    void fakeAdapterRequiresTheTestProfile() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/specagent/testing/FakeModelAdapter.java"));
        org.assertj.core.api.Assertions.assertThat(source)
                .contains("@Profile(\"test\")")
                .contains("havingValue = \"fake\"")
                .doesNotContain("matchIfMissing = true");
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
            .that().resideInAPackage("com.specagent.api..")
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
            .resideInAnyPackage("com.specagent.context..", "com.specagent.credential..")
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
    void readModelMustNotDependOnApi() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.readmodel..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.specagent.api..")
            .because("The read-model/application layer must not depend on the "
                    + "outermost HTTP API boundary; query failures stay "
                    + "read-model-neutral and are mapped at the API edge");

        rule.check(CLASSES);
    }

    @Test
    void controllersMustNotDependOnModelGateway() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.api..")
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
}

package com.specagent.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

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
}
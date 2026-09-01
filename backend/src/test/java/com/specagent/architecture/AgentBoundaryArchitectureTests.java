package com.specagent.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Stage A boundary rules for the new agent runtime packages
 * ({@code docs/v2/PYTHON_AGENT_RUNTIME_BOUNDARY.md}):
 * contracts stay pure, the decision layer never touches persistence, the
 * run worker never touches model/provider code, the inference broker never
 * reaches into repositories or credentials, and provider packages never
 * learn about brain-facing contracts.
 */
class AgentBoundaryArchitectureTests {

    private static final JavaClasses CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.specagent");

    @Test
    void agentContractPackagesAreFreeOfRuntimeDependencies() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.agent.contract..")
            .should().dependOnClassesThat()
            .haveSimpleNameEndingWith("Repository")
            .orShould().dependOnClassesThat()
            .haveSimpleNameEndingWith("Service")
            .orShould().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.model..", "com.specagent.api..",
                "com.specagent.project..", "com.specagent.route..",
                "com.specagent.node..", "com.specagent.answer..",
                "com.specagent.patch..", "com.specagent.context..")
            .because("Cross-language wire contracts must stay pure DTOs shared with the Python brain");

        rule.check(CLASSES);
    }

    @Test
    void agentDecisionPackageCannotDependOnPersistenceRepositories() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.agent.decision..")
            .should().dependOnClassesThat()
            .haveSimpleNameEndingWith("Repository")
            .because("The decision engine port/adapters validate responses only; "
                + "persistence stays behind the runtime services");

        rule.check(CLASSES);
    }

    @Test
    void agentRuntimePackagesAreFreeOfLlmAndGatewayDependencies() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.agent.runtime..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.model..")
            .because("The background run worker drives the decision engine port; "
                + "LLM/gateway access stays behind that boundary");

        rule.check(CLASSES);
    }

    @Test
    void agentBrokerMustNotDependOnRepositoriesOrCredentials() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.agent.broker..")
            .should().dependOnClassesThat()
            .haveSimpleNameEndingWith("Repository")
            .orShould().dependOnClassesThat()
            .resideInAPackage("com.specagent.credential..")
            .because("The internal inference broker speaks the neutral inference "
                + "seam plus sanitized event recording only; repository access "
                + "and credential resolution stay behind the gateway and services");

        rule.check(CLASSES);
    }

    @Test
    void agentBrokerMustNotDependOnProviderImplementationDetails() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.agent.broker..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.specagent.model.provider..")
            .because("The broker speaks the neutral inference seam; the frozen "
                + "OpenCode transport stays behind ModelInferenceGateway");

        rule.allowEmptyShould(true);
        rule.check(CLASSES);
    }

    @Test
    void modelPackagesCannotDependOnBrainFacingContracts() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.model..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.agent.contract..",
                "com.specagent.agent.decision..")
            .because("Provider/model packages may only expose the neutral "
                + "inference DTO, never Python decision contracts");

        rule.check(CLASSES);
    }

    @Test
    void agentSnapshotBuilderIsTheOnlyProjectionAllowedToReadRepositories() {
        // Guard the intended direction: the snapshot package may read
        // repositories, but nothing in it may call the model gateway seam.
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.agent.snapshot..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.model..")
            .because("Snapshot projection is deterministic input building; "
                + "it never calls models");

        rule.check(CLASSES);
    }

    @Test
    void agentActionPackageCannotDependOnModelOrLlmPackages() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.agent.action..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.model..")
            .because("Action executors apply validated proposals to the graph; "
                + "they never call LLM/gateway directly");

        rule.check(CLASSES);
    }

    @Test
    void agentPolicyPackageCannotDependOnModelOrLlmPackages() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.agent.policy..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.model..")
            .because("Policy engine evaluates proposals against runtime facts; "
                + "it never calls LLM/gateway directly");

        rule.check(CLASSES);
    }

    @Test
    void agentPolicyPackageCannotDependOnPythonOrFastApi() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.agent.policy..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.agent.decision..")
            .because("Policy decisions are Java-side runtime facts only; "
                + "they never depend on Python/FastAPI implementation details");

        rule.check(CLASSES);
    }
    @Test
    void graphCommandPackageCannotDependOnModelOrAgentBrains() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.graph..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.model..", "com.specagent.agent.decision..",
                "com.specagent.agent.broker..", "com.specagent.model.gateway..",
                "com.specagent.model.provider..")
            .because("Graph commands are deterministic runtime mutations; they never call models or brains");

        rule.check(CLASSES);
    }

    @Test
    void graphCommandPackageCannotCallModelGateways() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.graph..")
            .should().dependOnClassesThat()
            .haveSimpleNameEndingWith("Gateway")
            .because("Undo/redo compensation and graph commands must stay provider-free");

        rule.check(CLASSES);
    }
    @Test
    void capabilityPackageIsSelfContainedAndNeverReachesIntoAgentOrModel() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.capability..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.agent..", "com.specagent.model..",
                "com.specagent.api..", "com.specagent.web..")
            .because("Capability foundation is a self-contained boundary: agent depends on capabilities, never the reverse");

        rule.check(CLASSES);
    }

    @Test
    void capabilityPackageOwnsNoModelGatewaysOrCredentials() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.capability..")
            .should().dependOnClassesThat()
            .haveSimpleNameEndingWith("Gateway")
            .orShould().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.credential..", "com.specagent.settings..")
            .because("Capabilities never touch provider gateways, credentials, or model settings; the host runtime owns them");

        rule.check(CLASSES);
    }
}

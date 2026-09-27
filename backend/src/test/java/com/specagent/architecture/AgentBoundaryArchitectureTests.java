package com.specagent.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 文件名:AgentBoundaryArchitectureTests.java
 *
 * 测试目标:用 ArchUnit 守护 agent 运行时各包的 Stage A 边界规则
 * (见 {@code docs/v2/PYTHON_AGENT_RUNTIME_BOUNDARY.md}),并按 2026-09
 * 业务模块合并更新:跨语言线级 DTO 位于 {@code agent.protocol},
 * 反射门禁位于 {@code agent.gates},模型输出词汇位于 {@code agent.decision}。
 *
 * 约束:契约包保持纯净、决策层不碰持久化、运行 worker 不碰模型/Provider 代码、
 * 推理 broker 不碰仓储或凭据、Provider 包不感知面向大脑的契约。
 */
class AgentBoundaryArchitectureTests {

    private static final JavaClasses CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.specagent");

    @Test
    void agentProtocolPackagesAreFreeOfRuntimeDependencies() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.agent.protocol..")
            .should().dependOnClassesThat()
            .haveSimpleNameEndingWith("Repository")
            .orShould().dependOnClassesThat()
            .haveSimpleNameEndingWith("Service")
            .orShould().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.model..", "com.specagent.web..",
                "com.specagent.workspace..")
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
            .resideInAnyPackage("com.specagent.connection.credentials..")
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
            .resideInAnyPackage("com.specagent.agent.protocol..",
                "com.specagent.agent.decision..")
            .because("Provider/model packages may only expose the neutral "
                + "inference DTO, never Python decision contracts");

        rule.check(CLASSES);
    }

    @Test
    void agentSnapshotBuilderIsTheOnlyProjectionAllowedToReadRepositories() {
        // 守护预期方向:snapshot 包可以读仓储,但其中任何代码都不得调用模型网关接缝。
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.agent.snapshot..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.model..")
            .because("Snapshot projection is deterministic input building; "
                + "it never calls models");

        rule.check(CLASSES);
    }

    @Test
    void agentActionAndGatesPackagesCannotDependOnModelOrLlmPackages() {
        for (String pkg : new String[] {
                "com.specagent.agent.action..", "com.specagent.agent.gates.."}) {
            ArchRule rule = noClasses()
                .that().resideInAPackage(pkg)
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.specagent.model..")
                .because("Action executors apply validated proposals to the graph and "
                    + "gates validate them against runtime facts; neither ever calls "
                    + "LLM/gateway directly");
            rule.allowEmptyShould(true);

            rule.check(CLASSES);
        }
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
    void capabilityPackageIsSelfContainedAndNeverReachesIntoAgentOrModel() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.capability..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.agent..", "com.specagent.model..",
                "com.specagent.web..")
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
            .resideInAnyPackage("com.specagent.connection.credentials..",
                "com.specagent.modelsettings..")
            .because("Capabilities never touch provider gateways, credentials, or model settings; the host runtime owns them");

        rule.check(CLASSES);
    }
}

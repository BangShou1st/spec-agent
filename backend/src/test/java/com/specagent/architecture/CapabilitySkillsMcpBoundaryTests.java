package com.specagent.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Phase 3 low-coupling guard: agent never reaches into MCP internals, Skill
 * never reaches into planner/policy/brain, capability never touches concrete
 * provider SDKs, and MCP never owns prompt/brain/policy wording.
 */
class CapabilitySkillsMcpBoundaryTests {

    private static final JavaClasses CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.specagent");

    @Test
    void agentPackagesNeverReachIntoMcpImplementation() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.agent..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.specagent.mcp..")
            .because("Agent consumes frozen Capability/Skill projections; "
                + "MCP transport/SDK details stay behind the provider boundary");

        rule.check(CLASSES);
    }

    @Test
    void agentPackagesNeverReachIntoConnectionPersistence() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.agent..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.connection.persistence..",
                "com.specagent.connection.credentials..")
            .because("Agent never reads connection rows or credential stores "
                + "directly; providers project availability");

        rule.check(CLASSES);
    }

    @Test
    void skillPackagesNeverReachIntoPlannerOrPolicy() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.skill..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.agent.decision..",
                "com.specagent.agent.policy..", "com.specagent.model..")
            .because("Skill owns packages/activation/resources; planning and "
                + "authorization stay outside it");

        rule.check(CLASSES);
    }

    @Test
    void capabilityNeverDependsOnConcreteProviderSdk() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.capability..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.mcp..",
                "com.specagent.connection..", "com.specagent.skill..")
            .because("Capability is the provider-neutral abstraction; concrete "
                + "providers plug in through the SPI, never the reverse");

        rule.check(CLASSES);
    }

    @Test
    void mcpNeverOwnsPromptBrainOrPolicy() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.specagent.mcp..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.specagent.agent.decision..",
                "com.specagent.agent.policy..", "com.specagent.model..")
            .because("MCP owns transport/discovery/normalization; prompts stay "
                + "assets and policy stays in the policy engine");

        rule.check(CLASSES);
    }

    @Test
    void mcpSdkTypesStayBehindTheMcpModule() {
        ArchRule rule = noClasses()
            .that().resideOutsideOfPackages("com.specagent.mcp..")
            .should().dependOnClassesThat()
            .resideInAPackage("io.modelcontextprotocol..")
            .because("MCP SDK types never leak into agent/brain/policy/skill/"
                + "capability APIs; the domain types are the only surface");

        rule.check(CLASSES);
    }
}

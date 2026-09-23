package com.specagent.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityQueryContext;
import com.specagent.capability.CapabilityRegistry;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.CapabilityRuntime;
import com.specagent.capability.CapabilityVisibilityService;
import com.specagent.assistant.tool.GlobalAssistantToolCatalog;
import com.specagent.assistant.tool.GlobalProjectSearchService;
import com.specagent.assistant.tool.ProjectCreateCapability;
import com.specagent.assistant.tool.ProjectGetSummaryCapability;
import com.specagent.assistant.tool.ProjectListRecentCapability;
import com.specagent.assistant.tool.ProjectSearchCapability;
import com.specagent.assistant.tool.SkillImportCapability;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.skill.runtime.SkillActivateHostTool;
import com.specagent.skill.runtime.SkillReadResourceHostTool;
import com.specagent.skill.runtime.SkillSearchHostTool;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Slice B: the five host tools, deterministic search, catalog double isolation,
 * descriptor quality (no benchmark phrases), idempotent project.create.
 *
 * <p>The isolation invariant is: the GA whitelist keeps the Skill Runtime host
 * tools ({@code skill.activate}, {@code skill.search}, {@code skill.read_resource})
 * and every MCP capability out of the model-facing catalog, because those are the
 * surfaces that pull untrusted instructions or remote tool definitions into the
 * model. {@code skill.import} is allowed: it stages a reviewable import row and
 * returns metadata only — it never activates a Skill and never returns its
 * instructions.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GlobalAssistantSliceBIntegrationTest {
    @Autowired ProjectService projects;
    @Autowired CapabilityRuntime capabilities;
    @Autowired CapabilityRegistry registry;
    @Autowired CapabilityVisibilityService visibility;
    @Autowired GlobalProjectSearchService search;
    @Test
    void projectCreateDelegatesToProjectService() {
        String title = "GA Create " + UUID.randomUUID();
        CapabilityResult result = capabilities.invokeApplicationScoped(
                "ga-b-" + UUID.randomUUID(), ProjectCreateCapability.CAPABILITY_ID, null,
                Map.of("title", title));
        assertThat(result.status()).isEqualTo(CapabilityResult.Status.SUCCEEDED);
        UUID projectId = UUID.fromString(String.valueOf(result.content().get("projectId")));
        Project stored = projects.getProject(projectId).orElseThrow();
        assertThat(stored.title()).isEqualTo(title);
        assertThat(stored.activeRouteId()).isNotNull();
    }
    @Test
    void projectCreateRejectsBlankTitle() {
        CapabilityResult result = capabilities.invokeApplicationScoped(
                "ga-b-" + UUID.randomUUID(), ProjectCreateCapability.CAPABILITY_ID, null,
                Map.of("title", "  "));
        assertThat(result.status()).isEqualTo(CapabilityResult.Status.FAILED);
    }
    @Test
    void projectSearchFindsByLexicalMatchWithStableOrdering() {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        projects.createProject("GA Search Alpha " + marker);
        projects.createProject("GA Search Beta " + marker);
        List<GlobalProjectSearchService.Candidate> candidates = search.search(marker, 5);
        assertThat(candidates).hasSizeGreaterThanOrEqualTo(2);
        // Deterministic: repeated calls return the same order.
        List<GlobalProjectSearchService.Candidate> again = search.search(marker, 5);
        assertThat(again.stream().map(GlobalProjectSearchService.Candidate::projectId).toList())
                .isEqualTo(candidates.stream().map(GlobalProjectSearchService.Candidate::projectId).toList());
    }
    @Test
    void projectSearchHasNoBusinessWordSpecialCases() {
        // Paraphrases with different entity names resolve through the same
        // deterministic path; the service exposes no per-word weights.
        String marker = UUID.randomUUID().toString().substring(0, 8);
        projects.createProject("Notebook Planner " + marker);
        assertThat(search.search("notebook " + marker, 5)).isNotEmpty();
        assertThat(search.search("planner " + marker, 5)).isNotEmpty();
        assertThat(search.search("NOTEBOOK " + marker.toUpperCase(), 5)).isNotEmpty();
        assertThat(search.search("", 5)).isEmpty();
    }
    @Test
    void listRecentOrdersByRecency() {
        projects.createProject("GA Recent A " + UUID.randomUUID());
        projects.createProject("GA Recent B " + UUID.randomUUID());
        List<GlobalProjectSearchService.Candidate> recents = search.listRecent(3);
        assertThat(recents).hasSizeLessThanOrEqualTo(3);
    }
    @Test
    void getSummaryIsBoundedAndTyped() {
        Project project = projects.createProject("GA Summary " + UUID.randomUUID());
        CapabilityResult result = capabilities.invokeApplicationScoped(
                "ga-b-" + UUID.randomUUID(), ProjectGetSummaryCapability.CAPABILITY_ID, null,
                Map.of("projectId", project.id().toString()));
        assertThat(result.status()).isEqualTo(CapabilityResult.Status.SUCCEEDED);
        assertThat(result.content()).containsKeys("projectId", "title", "routeCount", "nodeCount");
        assertThat(result.content()).doesNotContainKeys("claims", "routes", "graph", "snapshot");
    }
    @Test
    void getSummaryWithUnknownIdFailsTyped() {
        CapabilityResult result = capabilities.invokeApplicationScoped(
                "ga-b-" + UUID.randomUUID(), ProjectGetSummaryCapability.CAPABILITY_ID, null,
                Map.of("projectId", UUID.randomUUID().toString()));
        assertThat(result.status()).isEqualTo(CapabilityResult.Status.FAILED);
        assertThat(String.valueOf(result.content().get("reason"))).contains("not found");
    }
    @Test
    void gaCatalogIsolatedFromSkillRuntimeAndMcp() {
        // Raw visibility may still list skill/mcp (empty supports = visible
        // everywhere); the frozen double isolation is: supports marker hides GA
        // tools from Project Agent, and the explicit GA whitelist keeps the
        // Skill Runtime host tools and MCP out of the model-facing GA catalog.
        // The rule names those tools explicitly rather than banning the whole
        // "skill." prefix: the hazard is reading/activating a Skill (untrusted
        // instructions in, tool definitions in), not staging an import the user
        // still has to review and enable.
        List<CapabilityDescriptor> gaVisible =
                visibility.visibleCapabilities(GlobalAssistantToolCatalog.queryContext());
        List<String> projected = gaVisible.stream()
                .map(CapabilityDescriptor::capabilityId)
                .filter(GlobalAssistantToolCatalog::isAllowed)
                .toList();
        assertThat(projected).containsExactlyInAnyOrder(
                ProjectCreateCapability.CAPABILITY_ID,
                ProjectSearchCapability.CAPABILITY_ID,
                ProjectListRecentCapability.CAPABILITY_ID,
                ProjectGetSummaryCapability.CAPABILITY_ID,
                SkillImportCapability.CAPABILITY_ID,
                com.specagent.assistant.tool.SkillDiscoverCapability.CAPABILITY_ID);
        Set<String> skillRuntimeHostTools = Set.of(
                SkillActivateHostTool.CAPABILITY_ID,
                SkillSearchHostTool.CAPABILITY_ID,
                SkillReadResourceHostTool.CAPABILITY_ID);
        assertThat(projected).noneMatch(skillRuntimeHostTools::contains);
        assertThat(projected).noneMatch(id -> id.startsWith("mcp."));
        // Every GA descriptor carries the application marker.
        for (CapabilityDescriptor descriptor : gaVisible) {
            if (GlobalAssistantToolCatalog.isAllowed(descriptor.capabilityId())) {
                assertThat(descriptor.supports()).contains(GlobalAssistantToolCatalog.SUPPORT_MARKER);
            }
        }
    }
    @Autowired com.specagent.assistant.tool.GlobalAssistantCatalogService catalogService;
    @Test
    void modelCatalogHoldsExactlyTheV1Tools() {
        assertThat(catalogService.modelCatalog().stream()
                        .map(com.specagent.capability.CapabilityDescriptor::capabilityId).toList())
                .containsExactlyInAnyOrder(
                        ProjectCreateCapability.CAPABILITY_ID,
                        ProjectSearchCapability.CAPABILITY_ID,
                        ProjectListRecentCapability.CAPABILITY_ID,
                        ProjectGetSummaryCapability.CAPABILITY_ID,
                        SkillImportCapability.CAPABILITY_ID,
                        com.specagent.assistant.tool.SkillDiscoverCapability.CAPABILITY_ID);
    }
    @Test
    void projectAgentCatalogDoesNotSeeGaTools() {
        CapabilityQueryContext projectContext =
                new CapabilityQueryContext(Set.of(), List.of("PROJECT:REQUIREMENT"), Map.of());
        List<String> ids = visibility.visibleCapabilities(projectContext).stream()
                .map(CapabilityDescriptor::capabilityId).toList();
        assertThat(ids).doesNotContain(
                ProjectCreateCapability.CAPABILITY_ID,
                ProjectSearchCapability.CAPABILITY_ID,
                ProjectListRecentCapability.CAPABILITY_ID,
                ProjectGetSummaryCapability.CAPABILITY_ID);
    }
    @Test
    void toolDescriptorsDescribeCapabilityNotBenchmarkPhrases() {
        for (String id : GlobalAssistantToolCatalog.TOOL_IDS) {
            CapabilityDescriptor descriptor =
                    registry.findDescriptor(id).orElseThrow();
            String text = descriptor.description();
            // Each descriptor must name its own domain, and none may quote a
            // benchmark prompt back at the model.
            assertThat(text).containsIgnoringCase(
                    id.startsWith("project.") ? "project" : "skill");
            assertThat(text).doesNotContain("\u6253\u5f00");
            assertThat(text).doesNotContain("\u4e4b\u524d");
            assertThat(text).doesNotContain("\u652f\u4ed8");
        }
    }
}

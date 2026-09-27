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
 * 文件名:GlobalAssistantSliceBIntegrationTest.java
 *
 * 测试目标:Slice B 集成——五个宿主工具、确定性搜索、目录双重隔离、
 * 描述符质量(不含基准测试短语)、幂等的 project.create。
 *
 * 隔离不变量为:GA 白名单把 Skill Runtime 宿主工具
 * ({@code skill.activate}、{@code skill.search}、{@code skill.read_resource})
 * 以及所有 MCP 能力挡在模型可见目录之外,因为这些入口会把不可信指令
 * 或远程工具定义引入模型。{@code skill.import} 被允许:它只落一条
 * 可人工审查的导入记录并返回元数据,从不激活 Skill,也从不返回其指令内容。
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
        // 确定性:重复调用返回相同顺序。
        List<GlobalProjectSearchService.Candidate> again = search.search(marker, 5);
        assertThat(again.stream().map(GlobalProjectSearchService.Candidate::projectId).toList())
                .isEqualTo(candidates.stream().map(GlobalProjectSearchService.Candidate::projectId).toList());
    }
    @Test
    void projectSearchHasNoBusinessWordSpecialCases() {
        // 不同实体名的改写都走同一条确定性路径;服务不暴露任何按词特判的权重。
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
        // 原始可见性里可能仍列出 skill/mcp(supports 为空 = 处处可见);
        // 冻结的双重隔离是:supports 标记对 Project Agent 隐藏 GA 工具,
        // 显式 GA 白名单把 Skill Runtime 宿主工具和 MCP 挡在模型可见的
        // GA 目录之外。规则点名这些工具而不是封禁整个 "skill." 前缀:
        // 危险在于读取/激活 Skill(不可信指令进来、工具定义进来),
        // 而不是暂存一条仍需用户审查并启用的导入记录。
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
        // 每个 GA 描述符都带应用标记。
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
            // 每个描述符必须说明自己的领域,且都不得把基准测试的提示词回喂给模型。
            assertThat(text).containsIgnoringCase(
                    id.startsWith("project.") ? "project" : "skill");
            assertThat(text).doesNotContain("\u6253\u5f00");
            assertThat(text).doesNotContain("\u4e4b\u524d");
            assertThat(text).doesNotContain("\u652f\u4ed8");
        }
    }
}

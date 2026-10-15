package com.specagent.assistant.tool;

import com.specagent.assistant.conversation.GaSourceDependencyGuard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:GaSourceDependencyContractTest.java
 *
 * 用途:GA 来源依赖账本的<b>契约与完整性不变量</b>(无 Spring 的确定性单测)。
 *
 * <p>账本重建依赖两条不变量,一旦被破坏,"撤权后不再复用旧项目观察"这个判定就不可靠,
 * 必须停下来改为显式依赖持久化,而不是在判定上加补丁:
 * <ol>
 *   <li><b>项目粒度的引用不会被展示截断。</b>账本取自
 *       {@code TOOL_COMPLETED.payload.resourceRefs},而该投影由
 *       {@link GlobalAssistantToolPresentation#projectResources} 产出、上限
 *       {@link GlobalAssistantToolPresentation#MAX_REFS}。因此每个项目范围工具
 *       允许返回的最大条目数不得超过该上限——特别是 GA 工具参数允许的 limit
 *       上限({@link GlobalAssistantToolCatalog#isValidLimit(Object)} 为 10)
 *       与 {@link GlobalProjectSearchService#MAX_LIMIT} 必须 ≤ MAX_REFS。</li>
 *   <li><b>能力分类必须覆盖目录,新增工具不得默认放行。</b>未分类的 capabilityId
 *       在判定里按"依赖未知"整会话隔离;因此目录里已有的能力必须显式落在
 *       "项目范围"或"确知无项目依赖"两类之一,否则所有会话都会被误封锁。</li>
 * </ol>
 */
class GaSourceDependencyContractTest {

    @Test
    void projectScopeCaptureCannotBeTruncatedByTheDisplayProjectionLimit() {
        assertThat(GlobalAssistantToolPresentation.MAX_REFS)
                .as("展示投影的项目引用上限必须覆盖工具允许的最大返回条数")
                .isGreaterThanOrEqualTo(GlobalProjectSearchService.MAX_LIMIT);
        // GA 工具参数契约允许的 limit 上限为 10(见 isValidLimit 的值域),因此上限必须 ≥10。
        assertThat(GlobalAssistantToolPresentation.MAX_REFS).isGreaterThanOrEqualTo(10);
        assertThat(GlobalAssistantToolCatalog.isValidLimit(10)).isTrue();
        assertThat(GlobalAssistantToolCatalog.isValidLimit(11)).isFalse();

        // 到达工具上限时,项目引用必须一条不少。
        assertThat(GlobalAssistantToolPresentation.projectResources("project.search",
                Map.of("candidates", candidates(GlobalProjectSearchService.MAX_LIMIT))))
                .hasSize(GlobalProjectSearchService.MAX_LIMIT);
        assertThat(GlobalAssistantToolPresentation.projectResources("project.list_recent",
                Map.of("projects", candidates(GlobalProjectSearchService.MAX_LIMIT))))
                .hasSize(GlobalProjectSearchService.MAX_LIMIT);
        // 检索来源卡片的 excerpt 会被截断(上限 8),但项目粒度依赖来自 candidates[].projectId,
        // 因此来源卡片截断不会丢失项目依赖。
        assertThat(GlobalAssistantToolPresentation.projectResources("project.content.discover",
                Map.of("candidates", candidates(4)))).hasSize(4);
    }

    @Test
    void everyCataloguedCapabilityIsExplicitlyClassifiedAsProjectScopedOrProjectFree() {
        List<String> known = new ArrayList<>(GlobalAssistantToolCatalog.TOOL_IDS);
        known.addAll(List.of("help.search", "project.content.discover", "web.search", "web.fetch",
                "user-input.request", "ui.navigate"));
        assertThat(known).as("目录与 broker 特殊分支必须都被显式分类")
                .allSatisfy(id -> assertThat(GaSourceDependencyGuard.CLASSIFIED_CAPABILITIES)
                        .as("capability %s 必须有明确分类,未分类会被判为依赖未知", id).contains(id));
        // 项目范围集合必须精确;漏掉一个项目工具会让它的观察不计入依赖。
        assertThat(GaSourceDependencyGuard.PROJECT_SCOPED_CAPABILITIES).containsExactlyInAnyOrder(
                "project.create", "project.search", "project.list_recent",
                "project.get_summary", "project.content.discover");
        // 这些能力的 TOOL_COMPLETED 本来就不带 resourceRefs 键,绝不能被当成"依赖未知",
        // 否则所有会话都会被封锁。
        assertThat(GaSourceDependencyGuard.PROJECT_SCOPED_CAPABILITIES)
                .doesNotContain("user-input.request", "ui.navigate", "help.search", "web.search", "web.fetch",
                        "skill.import", "skill.import.discover");
        // 全部已分类能力必须都在目录白名单内(broker 特殊分支除外)。
        assertThat(GaSourceDependencyGuard.CLASSIFIED_CAPABILITIES)
                .allSatisfy(id -> assertThat(GlobalAssistantToolCatalog.isAllowed(id)
                        || Set.of("user-input.request", "ui.navigate").contains(id))
                        .as("分类不得包含目录之外的未知能力: %s", id).isTrue());
    }

    private static List<Map<String, Object>> candidates(int count) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("projectId", UUID.randomUUID().toString());
            item.put("title", "candidate-" + index);
            item.put("updatedAt", "2026-10-15T00:00:00Z");
            items.add(item);
        }
        return items;
    }
}

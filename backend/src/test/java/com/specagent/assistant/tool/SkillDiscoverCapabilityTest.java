package com.specagent.assistant.tool;

import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.SideEffectClass;
import com.specagent.skill.importing.SkillImportException;
import com.specagent.skill.registry.SkillImportService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:SkillDiscoverCapabilityTest.java
 *
 * 测试目标:skill 发现能力是 skill 名称的佐证来源——只读、不暂存、
 * 有界。这些用例钉死:它不会暂存、安装或执行任何东西,
 * 不可信的仓库元数据只以有界形式进入上下文。
 */
class SkillDiscoverCapabilityTest {

    private static final String URL = "https://example.com/skills.git";

    @Test
    void unknownArgumentsAreRejectedBeforeAnyRepositoryWork() {
        SkillImportService imports = Mockito.mock(SkillImportService.class);
        CapabilityResult result = invoke(new SkillDiscoverCapability(imports),
                Map.of("url", URL, "skill", "skills/brainstorming"));

        assertThat(result.status()).isEqualTo(CapabilityResult.Status.FAILED);
        assertThat(result.content().get("errorCode")).isEqualTo("TOOL_ARGUMENT_INVALID");
        Mockito.verifyNoInteractions(imports);
    }

    @Test
    void urlIsRequiredAndBounded() {
        SkillImportService imports = Mockito.mock(SkillImportService.class);
        SkillDiscoverCapability capability = new SkillDiscoverCapability(imports);

        assertThat(invoke(capability, Map.of()).content().get("errorCode"))
                .isEqualTo("TOOL_ARGUMENT_INVALID");
        assertThat(invoke(capability, Map.of("url", "   ")).content().get("errorCode"))
                .isEqualTo("TOOL_ARGUMENT_INVALID");
        assertThat(invoke(capability, Map.of("url", "x".repeat(501))).content().get("errorCode"))
                .isEqualTo("TOOL_ARGUMENT_INVALID");
        Mockito.verifyNoInteractions(imports);
    }

    @Test
    void discoveryListsCandidatesAndStagesNothing() {
        SkillImportService imports = Mockito.mock(SkillImportService.class);
        Mockito.when(imports.discoverGit(URL, null)).thenReturn(discovery(List.of(
                candidate("skills/brainstorming", "brainstorming", true),
                candidate("skills/testing", "testing", true))));

        CapabilityResult result = invoke(new SkillDiscoverCapability(imports), Map.of("url", URL));

        assertThat(result.status()).isEqualTo(CapabilityResult.Status.SUCCEEDED);
        assertThat(result.content()).containsEntry("url", URL);
        assertThat(result.content()).containsEntry("candidateCount", 2);
        assertThat(result.content()).containsEntry("suggestedPath", "skills/brainstorming");
        assertThat(result.content()).doesNotContainKey("stagedImportId");
        assertThat(result.content()).doesNotContainKey("requiresChoice");
        Mockito.verify(imports, Mockito.never())
                .stageGit(Mockito.anyString(), Mockito.any(), Mockito.anyString());
    }

    @Test
    void candidateMetadataIsBounded() {
        SkillImportService imports = Mockito.mock(SkillImportService.class);
        String longDescription = "d".repeat(400);
        Mockito.when(imports.discoverGit(URL, "main")).thenReturn(discovery(List.of(
                candidate("skills/a", "a", longDescription, true),
                candidate("skills/broken", "broken", "malformed", false))));

        CapabilityResult result = invoke(new SkillDiscoverCapability(imports),
                Map.of("url", URL, "ref", "main"));

        assertThat(result.content()).containsEntry("ref", "main");
        assertThat(result.content().get("commitSha")).isEqualTo("abc123");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) result.content().get("candidates");
        assertThat(candidates).hasSize(2);
        assertThat((String) candidates.get(0).get("description"))
                .hasSize(160 + 1).endsWith("…");
        assertThat(candidates.get(1)).containsEntry("parseable", false);
    }

    @Test
    void discoveryFailureIsReportedAsExecution() {
        SkillImportService imports = Mockito.mock(SkillImportService.class);
        Mockito.when(imports.discoverGit(URL, null))
                .thenThrow(new SkillImportException("Repository URL must be HTTPS"));

        CapabilityResult result = invoke(new SkillDiscoverCapability(imports), Map.of("url", URL));

        assertThat(result.status()).isEqualTo(CapabilityResult.Status.FAILED);
        assertThat(result.content().get("errorCode")).isEqualTo("TOOL_EXECUTION_FAILED");
        assertThat(String.valueOf(result.content().get("reason"))).contains("must be HTTPS");
    }

    @Test
    void descriptorIsReadOnlyWithoutSideEffects() {
        CapabilityDescriptor descriptor = new SkillDiscoverCapability(
                Mockito.mock(SkillImportService.class)).descriptor();

        assertThat(descriptor.capabilityId()).isEqualTo("skill.import.discover");
        assertThat(descriptor.readOnly()).isTrue();
        assertThat(descriptor.sideEffectClass()).isEqualTo(SideEffectClass.NONE);
        assertThat(descriptor.supports()).contains(GlobalAssistantToolCatalog.SUPPORT_MARKER);
    }

    private static CapabilityResult invoke(SkillDiscoverCapability capability,
                                           Map<String, Object> arguments) {
        return capability.invoke(new CapabilityInvocation(UUID.randomUUID(), "key-1",
                SkillDiscoverCapability.CAPABILITY_ID, null, null, arguments));
    }

    private static SkillImportService.DiscoveryResult discovery(
            List<SkillImportService.DiscoveryCandidate> candidates) {
        return new SkillImportService.DiscoveryResult("abc123", "skills/brainstorming", candidates);
    }

    private static SkillImportService.DiscoveryCandidate candidate(
            String path, String name, boolean parseable) {
        return candidate(path, name, "desc", parseable);
    }

    private static SkillImportService.DiscoveryCandidate candidate(
            String path, String name, String description, boolean parseable) {
        return new SkillImportService.DiscoveryCandidate(path, name, description,
                "NESTED", null, 3, parseable);
    }
}

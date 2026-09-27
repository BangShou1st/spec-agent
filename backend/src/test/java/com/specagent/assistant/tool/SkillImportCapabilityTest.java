package com.specagent.assistant.tool;

import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityResult;
import com.specagent.skill.importing.SkillImportException;
import com.specagent.skill.registry.SkillImportService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:SkillImportCapabilityTest.java
 *
 * 测试目标:助手可以发起 Skill 导入,但绝不能替用户完成导入。
 * 这些用例钉死这条边界:暂存只是落一条待人工审查的记录、
 * 有歧义时把选择交还给用户,非法参数则根本不会触及导入服务。
 */
class SkillImportCapabilityTest {

    private static final String URL = "https://example.com/skills.git";
    private static final UUID STAGED_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void unknownArgumentsAreRejectedBeforeAnyRepositoryWork() {
        SkillImportService imports = Mockito.mock(SkillImportService.class);
        CapabilityResult result = invoke(new SkillImportCapability(imports),
                Map.of("url", URL, "package", "x"));

        assertThat(result.status()).isEqualTo(CapabilityResult.Status.FAILED);
        assertThat(result.content().get("errorCode")).isEqualTo("TOOL_ARGUMENT_INVALID");
        Mockito.verifyNoInteractions(imports);
    }

    @Test
    void urlIsRequiredAndBounded() {
        SkillImportService imports = Mockito.mock(SkillImportService.class);
        SkillImportCapability capability = new SkillImportCapability(imports);

        assertThat(invoke(capability, Map.of()).content().get("errorCode"))
                .isEqualTo("TOOL_ARGUMENT_INVALID");
        assertThat(invoke(capability, Map.of("url", "   ")).content().get("errorCode"))
                .isEqualTo("TOOL_ARGUMENT_INVALID");
        assertThat(invoke(capability, Map.of("url", "x".repeat(501))).content().get("errorCode"))
                .isEqualTo("TOOL_ARGUMENT_INVALID");
        Mockito.verifyNoInteractions(imports);
    }

    @Test
    void singleSkillRepositoryIsStagedDirectly() {
        SkillImportService imports = Mockito.mock(SkillImportService.class);
        Mockito.when(imports.discoverGit(URL, null)).thenReturn(discovery("skills/only",
                List.of(candidate("skills/only", "only"))));
        Mockito.when(imports.stageGit(URL, null, "skills/only")).thenReturn(staged());

        CapabilityResult result = invoke(new SkillImportCapability(imports), Map.of("url", URL));

        assertThat(result.status()).isEqualTo(CapabilityResult.Status.SUCCEEDED);
        assertThat(result.content()).containsEntry("stagedImportId", STAGED_ID.toString());
        assertThat(result.content()).containsEntry("skillPath", "skills/only");
        assertThat(result.content()).containsEntry("requiresChoice", false);
        assertThat(result.sourceRefs()).containsExactly("skill_import:" + STAGED_ID);
    }

    @Test
    void aLibraryAsksTheUserInsteadOfGuessing() {
        SkillImportService imports = Mockito.mock(SkillImportService.class);
        Mockito.when(imports.discoverGit(URL, null)).thenReturn(discovery("skills/a",
                List.of(candidate("skills/a", "a"), candidate("skills/b", "b"))));

        CapabilityResult result = invoke(new SkillImportCapability(imports), Map.of("url", URL));

        assertThat(result.status()).isEqualTo(CapabilityResult.Status.SUCCEEDED);
        assertThat(result.content()).containsEntry("requiresChoice", true);
        assertThat(result.content()).containsEntry("candidateCount", 2);
        assertThat(result.content()).doesNotContainKey("stagedImportId");
        // 用户尚未做出选择前,什么都不会被暂存。
        Mockito.verify(imports, Mockito.never())
                .stageGit(Mockito.anyString(), Mockito.any(), Mockito.anyString());
    }

    @Test
    void anExplicitSkillNameSelectsWithoutAsking() {
        SkillImportService imports = Mockito.mock(SkillImportService.class);
        Mockito.when(imports.discoverGit(URL, null)).thenReturn(discovery("skills/a",
                List.of(candidate("skills/a", "brainstorming"), candidate("skills/b", "testing"))));
        Mockito.when(imports.stageGit(URL, null, "skills/b")).thenReturn(staged());

        CapabilityResult result = invoke(new SkillImportCapability(imports),
                Map.of("url", URL, "skill", "testing"));

        assertThat(result.status()).isEqualTo(CapabilityResult.Status.SUCCEEDED);
        assertThat(result.content()).containsEntry("stagedImportId", STAGED_ID.toString());
        assertThat(result.content()).containsEntry("skillPath", "skills/b");
    }

    @Test
    void anExplicitPathWithTheLeadingDirectoryIsAccepted() {
        SkillImportService imports = Mockito.mock(SkillImportService.class);
        Mockito.when(imports.discoverGit(URL, null)).thenReturn(discovery("skills/a",
                List.of(candidate("skills/a", "a"), candidate("skills/b", "b"))));
        Mockito.when(imports.stageGit(URL, null, "skills/a")).thenReturn(staged());

        CapabilityResult result = invoke(new SkillImportCapability(imports),
                Map.of("url", URL, "skill", "skills/a"));

        assertThat(result.content()).containsEntry("skillPath", "skills/a");
    }

    @Test
    void anUnknownSkillListsWhatTheRepositoryOffers() {
        SkillImportService imports = Mockito.mock(SkillImportService.class);
        Mockito.when(imports.discoverGit(URL, null)).thenReturn(discovery(null,
                List.of(candidate("skills/a", "a"), candidate("skills/b", "b"))));

        CapabilityResult result = invoke(new SkillImportCapability(imports),
                Map.of("url", URL, "skill", "nope"));

        assertThat(result.status()).isEqualTo(CapabilityResult.Status.FAILED);
        assertThat(result.content().get("errorCode")).isEqualTo("TOOL_ARGUMENT_INVALID");
        assertThat(String.valueOf(result.content().get("reason")))
                .contains("skills/a", "skills/b");
    }

    @Test
    void aRepositoryWithoutAnyUsableSkillFailsAsExecution() {
        SkillImportService imports = Mockito.mock(SkillImportService.class);
        Mockito.when(imports.discoverGit(URL, null)).thenReturn(discovery(null, List.of()));
        assertThat(invoke(new SkillImportCapability(imports), Map.of("url", URL))
                .content().get("errorCode")).isEqualTo("TOOL_EXECUTION_FAILED");

        SkillImportService broken = Mockito.mock(SkillImportService.class);
        Mockito.when(broken.discoverGit(URL, null))
                .thenReturn(discovery(null, List.of(candidateUnparsable("docs/x"))));
        assertThat(invoke(new SkillImportCapability(broken), Map.of("url", URL))
                .content().get("errorCode")).isEqualTo("TOOL_EXECUTION_FAILED");
    }

    @Test
    void anImportFailureIsReportedWithoutLeakingStackDetails() {
        SkillImportService imports = Mockito.mock(SkillImportService.class);
        Mockito.when(imports.discoverGit(URL, null)).thenReturn(discovery("skills/a",
                List.of(candidate("skills/a", "a"))));
        Mockito.when(imports.stageGit(URL, null, "skills/a"))
                .thenThrow(new SkillImportException(
                        "Git repository is not a single Skill package"));

        CapabilityResult result = invoke(new SkillImportCapability(imports), Map.of("url", URL));

        assertThat(result.status()).isEqualTo(CapabilityResult.Status.FAILED);
        assertThat(result.content().get("errorCode")).isEqualTo("TOOL_EXECUTION_FAILED");
        assertThat(String.valueOf(result.content().get("reason")))
                .contains("not a single Skill package");
    }

    private static CapabilityResult invoke(SkillImportCapability capability,
                                           Map<String, Object> arguments) {
        return capability.invoke(new CapabilityInvocation(UUID.randomUUID(), "key-1",
                SkillImportCapability.CAPABILITY_ID, null, null, arguments));
    }

    private static SkillImportService.DiscoveryResult discovery(
            String suggested, List<SkillImportService.DiscoveryCandidate> candidates) {
        return new SkillImportService.DiscoveryResult("abc123", suggested, candidates);
    }

    private static SkillImportService.DiscoveryCandidate candidate(String path, String name) {
        return new SkillImportService.DiscoveryCandidate(path, name, "desc", "NESTED", null, 3, true);
    }

    private static SkillImportService.DiscoveryCandidate candidateUnparsable(String path) {
        return new SkillImportService.DiscoveryCandidate(path, path, "", "NESTED", null, 1, false);
    }

    private static SkillImportService.StagedResult staged() {
        return new SkillImportService.StagedResult(STAGED_ID, "brainstorming", "desc", "hash", 3, 100);
    }
}

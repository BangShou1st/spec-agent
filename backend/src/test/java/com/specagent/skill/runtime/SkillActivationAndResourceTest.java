package com.specagent.skill.runtime;

import com.specagent.skill.config.SkillProperties;
import com.specagent.skill.domain.Skill;
import com.specagent.skill.domain.SkillSourceKind;
import com.specagent.skill.domain.SkillVersion;
import com.specagent.skill.persistence.SkillRepository;
import com.specagent.skill.registry.SkillQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Activation rules: only installed + enabled + visible Skills activate;
 * instructions stay bounded; provenance is recorded; resources are read on
 * demand with traversal defense.
 */
class SkillActivationAndResourceTest {

    private static final UUID VERSION_ID =
            UUID.fromString("11000000-0000-0000-0000-000000000001");

    private SkillQueryService queryService;
    private SkillRepository repository;
    private SkillProperties properties;
    private SkillActivationService activation;

    @BeforeEach
    void setUp() {
        queryService = mock(SkillQueryService.class);
        repository = mock(SkillRepository.class);
        properties = SkillProperties.defaults();
        activation = new SkillActivationService(queryService, repository, properties);
    }

    @Test
    void activateDisabledSkillIsRejected() {
        when(queryService.findSkill("sk-disabled")).thenReturn(Optional.of(
                skill("sk-disabled", "Disabled", false)));

        assertThatThrownBy(() -> activation.activate(UUID.randomUUID(), null, "sk-disabled"))
                .isInstanceOf(SkillNotVisibleException.class);
    }

    @Test
    void activateUnknownSkillIsRejected() {
        when(queryService.findSkill("sk-unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> activation.activate(UUID.randomUUID(), null, "sk-unknown"))
                .isInstanceOf(SkillNotVisibleException.class);
    }

    @Test
    void activateReturnsBoundedInstructionsAndRecordsProvenance() {
        when(queryService.findSkill("sk-ok")).thenReturn(Optional.of(
                skill("sk-ok", "Ok", true)));
        when(queryService.findVersion(VERSION_ID)).thenReturn(Optional.of(
                new SkillVersion(VERSION_ID, UUID.randomUUID(), 3, "hash-abc",
                        "{}", "do this\nand that", "src", 1, 10, Instant.EPOCH)));
        when(queryService.listFileSummaries(VERSION_ID)).thenReturn(List.of(
                new SkillRepository.FileSummary("references/a.md",
                        com.specagent.skill.domain.SkillPackageFile.FileKind.TEXT,
                        4, "sha-a")));

        SkillActivationService.ActivatedSkill activated =
                activation.activate(UUID.randomUUID(), null, "sk-ok");

        assertThat(activated.skillId()).isEqualTo("sk-ok");
        assertThat(activated.versionNo()).isEqualTo(3);
        assertThat(activated.contentHash()).isEqualTo("hash-abc");
        assertThat(activated.instructions()).contains("do this");
        assertThat(activated.resources()).containsExactly("references/a.md");
        verify(repository, times(1)).recordActivation(any(), any(), any(), any(), any(), any());
    }

    @Test
    void activateBoundsOversizedInstructions() {
        properties.setActivationMaxInstructionBytes(8);
        when(queryService.findSkill("sk-big")).thenReturn(Optional.of(
                skill("sk-big", "Big", true)));
        when(queryService.findVersion(VERSION_ID)).thenReturn(Optional.of(
                new SkillVersion(VERSION_ID, UUID.randomUUID(), 1, "h",
                        "{}", "abcdefghijklmnopqrstuvwxyz", "src", 1, 30, Instant.EPOCH)));
        when(queryService.listFileSummaries(VERSION_ID)).thenReturn(List.of());

        SkillActivationService.ActivatedSkill activated =
                activation.activate(UUID.randomUUID(), null, "sk-big");

        assertThat(activated.instructions()).hasSize(9); // 8 chars + …
    }

    @Test
    void resourceReadRejectsTraversal() {
        SkillResourceService resourceService =
                new SkillResourceService(queryService, properties);

        assertThatThrownBy(() -> resourceService.readResource(VERSION_ID, "../etc/passwd"))
                .isInstanceOf(SkillResourceRejectedException.class);
        assertThatThrownBy(() -> resourceService.readResource(VERSION_ID, "SKILL.md"))
                .isInstanceOf(SkillResourceRejectedException.class);
        assertThatThrownBy(() -> resourceService.readResource(VERSION_ID, "/absolute"))
                .isInstanceOf(SkillResourceRejectedException.class);
    }

    @Test
    void resourceReadReturnsBoundedTextWithProvenance() {
        SkillResourceService resourceService =
                new SkillResourceService(queryService, properties);
        when(queryService.findPackageFile(VERSION_ID, "references/guide.md")).thenReturn(
                Optional.of(new com.specagent.skill.domain.SkillPackageFile(
                        UUID.randomUUID(), VERSION_ID, "references/guide.md",
                        com.specagent.skill.domain.SkillPackageFile.FileKind.TEXT,
                        12, "expected-hash", "guide content".getBytes())));

        SkillResourceService.ResourceRead read =
                resourceService.readResource(VERSION_ID, "references/guide.md");

        assertThat(read.content()).isEqualTo("guide content");
        assertThat(read.sha256()).isEqualTo("expected-hash");
        assertThat(read.versionId()).isEqualTo(VERSION_ID.toString());
    }

    @Test
    void resourceReadRejectsBinaryInPhaseOne() {
        SkillResourceService resourceService =
                new SkillResourceService(queryService, properties);
        when(queryService.findPackageFile(VERSION_ID, "assets/logo.png")).thenReturn(
                Optional.of(new com.specagent.skill.domain.SkillPackageFile(
                        UUID.randomUUID(), VERSION_ID, "assets/logo.png",
                        com.specagent.skill.domain.SkillPackageFile.FileKind.BINARY,
                        4, "h", new byte[]{0, 1, 2, 3})));

        assertThatThrownBy(() -> resourceService.readResource(VERSION_ID, "assets/logo.png"))
                .isInstanceOf(SkillResourceRejectedException.class)
                .hasMessageContaining("text");
    }

    @Test
    void resourceReadMissingFileIsRejected() {
        SkillResourceService resourceService =
                new SkillResourceService(queryService, properties);
        when(queryService.findPackageFile(VERSION_ID, "missing.txt"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> resourceService.readResource(VERSION_ID, "missing.txt"))
                .isInstanceOf(SkillResourceRejectedException.class)
                .hasMessageContaining("not found");
    }

    private Skill skill(String skillId, String name, boolean enabled) {
        return new Skill(UUID.randomUUID(), skillId, name, "desc",
                SkillSourceKind.UPLOAD_ZIP, "source:" + skillId,
                enabled ? VERSION_ID : null, enabled, Instant.EPOCH, Instant.EPOCH);
    }
}
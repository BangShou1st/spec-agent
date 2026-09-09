package com.specagent.skill.runtime;

import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityResult;
import com.specagent.common.Ids;
import com.specagent.skill.config.SkillProperties;
import com.specagent.skill.discovery.SkillDiscoveryContext;
import com.specagent.skill.discovery.SkillDiscoveryService;
import com.specagent.skill.discovery.SkillSearchCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * skill.search contract: metadata-only candidates, never auto-activation,
 * blank queries fail closed, results bounded.
 */
@ExtendWith(MockitoExtension.class)
class SkillSearchHostToolTest {

    @Mock
    private SkillDiscoveryService discoveryService;

    private SkillSearchHostTool tool() {
        return new SkillSearchHostTool(discoveryService, SkillProperties.defaults());
    }

    private CapabilityInvocation invocation(Map<String, Object> arguments) {
        return new CapabilityInvocation(Ids.random(), "search-key-1",
                SkillSearchHostTool.CAPABILITY_ID, UUID.randomUUID(),
                UUID.randomUUID(), arguments);
    }

    @Test
    void searchReturnsMetadataOnly() {
        when(discoveryService.search(any())).thenReturn(List.of(
                new SkillSearchCandidate("sk-1", "migration-safety",
                        "数据库迁移安全检查", "migration.sql"),
                new SkillSearchCandidate("sk-2", "doc-review", "文档评审", null)));

        CapabilityResult result = tool().invoke(invocation(Map.of("query", "迁移安全")));

        assertThat(result.status()).isEqualTo(CapabilityResult.Status.SUCCEEDED);
        assertThat(String.valueOf(result.content())).contains("migration-safety");
        assertThat(String.valueOf(result.content())).doesNotContain("SKILL.md");
        assertThat(result.provenance()).containsEntry("kind", "SKILL_SEARCH");
    }

    @Test
    void blankQueryFailsClosed() {
        CapabilityResult result = tool().invoke(invocation(Map.of("query", "  ")));
        assertThat(result.status()).isEqualTo(CapabilityResult.Status.FAILED);
    }

    @Test
    void descriptorIsReadOnlyNone() {
        var descriptor = tool().descriptor();
        assertThat(descriptor.capabilityId()).isEqualTo("skill.search");
        assertThat(descriptor.readOnly()).isTrue();
        assertThat(descriptor.inputSchema()).containsKey("query");
    }
}

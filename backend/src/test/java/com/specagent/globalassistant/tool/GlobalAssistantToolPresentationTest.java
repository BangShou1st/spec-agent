package com.specagent.globalassistant.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalAssistantToolPresentationTest {

    @Test
    void shippedToolsHaveProductLabels() {
        assertThat(GlobalAssistantToolPresentation.runningMessage("project.list_recent")).isEqualTo("Listing recent projects");
        assertThat(GlobalAssistantToolPresentation.resultKind("project.search")).isEqualTo("PROJECT_LIST");
        assertThat(GlobalAssistantToolPresentation.resultKind("project.create")).isEqualTo("PROJECT");
    }

    @Test
    void unknownCapabilityFallsBackWithoutCrashing() {
        assertThat(GlobalAssistantToolPresentation.runningMessage("future.tool")).isEqualTo("Working");
        assertThat(GlobalAssistantToolPresentation.resultKind("future.tool")).isNull();
        assertThat(GlobalAssistantToolPresentation.runningMessage(null)).isEqualTo("Working");
    }

    private static Map<String, Object> projectItem(String id, String title) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projectId", id);
        m.put("title", title);
        m.put("updatedAt", "2026-09-10T00:00:00Z");
        return m;
    }

    private static boolean hasUnpaired(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)
                    && (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1)))) {
                return true;
            }
            if (Character.isLowSurrogate(c)
                    && (i == 0 || !Character.isHighSurrogate(s.charAt(i - 1)))) {
                return true;
            }
        }
        return false;
    }

    @Test
    void listShapeProjectsRefs() {
        String id = UUID.randomUUID().toString();
        Map<String, Object> content = Map.of("projects", List.of(projectItem(id, "Atlas")));
        var refs = GlobalAssistantToolPresentation.projectResources("project.list_recent", content);
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0)).containsEntry("kind", "PROJECT").containsEntry("id", id).containsEntry("label", "Atlas");
    }

    @Test
    void searchUsesCandidatesKey() {
        String id = UUID.randomUUID().toString();
        Map<String, Object> content = Map.of("candidates", List.of(projectItem(id, "Notes")));
        var refs = GlobalAssistantToolPresentation.projectResources("project.search", content);
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0)).containsEntry("id", id);
    }

    @Test
    void singleShapeWrapsContent() {
        String id = UUID.randomUUID().toString();
        Map<String, Object> content = new LinkedHashMap<>(projectItem(id, "Created"));
        assertThat(GlobalAssistantToolPresentation.projectResources("project.create", content)).hasSize(1);
        assertThat(GlobalAssistantToolPresentation.projectResources("project.get_summary", content)).hasSize(1);
    }

    @Test
    void unknownCapabilityProjectsEmpty() {
        assertThat(GlobalAssistantToolPresentation.projectResources("future.tool", Map.of("projects", List.of()))).isEmpty();
        assertThat(GlobalAssistantToolPresentation.projectResources(null, Map.of())).isEmpty();
        assertThat(GlobalAssistantToolPresentation.candidatePairs("future.tool", Map.of())).isEmpty();
        assertThat(GlobalAssistantToolPresentation.directProjectId("future.tool", Map.of())).isNull();
        assertThat(GlobalAssistantToolPresentation.resultShapeOf("future.tool")).isNull();
    }

    @Test
    void invalidUuidSkipped() {
        Map<String, Object> content = Map.of("projects", List.of(projectItem("not-a-uuid", "Bad")));
        assertThat(GlobalAssistantToolPresentation.projectResources("project.list_recent", content)).isEmpty();
    }

    @Test
    void labelTruncationIsCodePointSafeAcrossBoundary() {
        String title = "x".repeat(199) + "\uD83D\uDE00" + "tail";
        String id = UUID.randomUUID().toString();
        Map<String, Object> content = Map.of("projects", List.of(projectItem(id, title)));
        var refs = GlobalAssistantToolPresentation.projectResources("project.list_recent", content);
        assertThat(refs).hasSize(1);
        String label = String.valueOf(refs.get(0).get("label"));
        assertThat(label.codePointCount(0, label.length())).isEqualTo(200);
        assertThat(hasUnpaired(label)).isFalse();
        assertThat(label.startsWith("x".repeat(199) + "\uD83D\uDE00")).isTrue();
    }

    @Test
    void pureEmojiLongTitleTruncatedSafely() {
        String id = UUID.randomUUID().toString();
        Map<String, Object> shortContent = Map.of("projects", List.of(projectItem(id, "\uD83D\uDE00".repeat(150))));
        String kept = String.valueOf(GlobalAssistantToolPresentation.projectResources("project.list_recent", shortContent).get(0).get("label"));
        assertThat(kept.codePointCount(0, kept.length())).isEqualTo(150);
        assertThat(hasUnpaired(kept)).isFalse();
        Map<String, Object> longContent = Map.of("projects", List.of(projectItem(id, "\uD83D\uDE00".repeat(250))));
        String cut = String.valueOf(GlobalAssistantToolPresentation.projectResources("project.list_recent", longContent).get(0).get("label"));
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(200);
        assertThat(hasUnpaired(cut)).isFalse();
    }

    @Test
    void candidatePairsAndDirectId() {
        String id = UUID.randomUUID().toString();
        Map<String, Object> search = Map.of("candidates", List.of(projectItem(id, "Notes")));
        assertThat(GlobalAssistantToolPresentation.candidatePairs("project.search", search)).hasSize(1);
        assertThat(GlobalAssistantToolPresentation.directProjectId("project.search", search)).isNull();
        Map<String, Object> single = new LinkedHashMap<>(projectItem(id, "Created"));
        assertThat(GlobalAssistantToolPresentation.candidatePairs("project.create", single)).isEmpty();
        assertThat(GlobalAssistantToolPresentation.directProjectId("project.create", single)).isEqualTo(id);
        assertThat(GlobalAssistantToolPresentation.directProjectId("project.create", Map.of())).isNull();
    }
}

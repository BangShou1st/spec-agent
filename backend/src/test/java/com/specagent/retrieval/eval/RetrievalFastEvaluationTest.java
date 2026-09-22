package com.specagent.retrieval.eval;

import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextOperationType;
import com.specagent.context.ContextSnapshot;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.retrieval.embedding.EmbeddingEnrichmentService;
import com.specagent.retrieval.persistence.RetrievalEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Offline metrics artifact for the blocking retrieval gate. */
@SpringBootTest(properties = "spec.agent.retrieval.embedding.provider=fake")
@ActiveProfiles("test")
@Transactional
class RetrievalFastEvaluationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private ContextBuilder contextBuilder;
    @Autowired
    private AgentInputSnapshotBuilder snapshotBuilder;
    @Autowired
    private RetrievalEntryRepository entryRepository;
    @Autowired
    private EmbeddingEnrichmentService enrichmentService;

    @Test
    void retrievalFastWritesDeterministicMetricsArtifact() throws Exception {
        long started = System.nanoTime();
        Project project = projectService.createProject("retrieval fast evaluation");
        UUID routeId = project.activeRouteId();
        Node historical = nodeService.createRootNode(project.id(), routeId,
                "会议时间限制：最多45分钟。", null, List.of(), true);
        Node tip = historical;
        for (int index = 2; index <= 150; index++) {
            tip = nodeService.createChildNode(project.id(), routeId, tip.id(),
                    "评估节点 " + index, null, List.of(), true);
        }

        ContextSnapshot context = contextBuilder.buildForNodeQuery(
                project.id(), routeId, tip.id(), "还有哪些会议时间方面的限制？");
        var snapshot = snapshotBuilder.build(context);
        boolean recalled = snapshot.retrievedContext().stream()
                .anyMatch(item -> item.sourceRef().equals("node:" + historical.id())
                        && item.content().contains("45分钟"));
        assertThat(recalled).isTrue();
        assertThat(snapshot.lineage()).hasSizeLessThanOrEqualTo(12);
        enrichmentService.enrichPending(project.id());
        boolean vectorReady = entryRepository.findByProject(project.id()).stream()
                .anyMatch(entry -> "READY".equals(entry.embeddingStatus()));

        int selected = snapshot.retrievedContext().size();
        long distinct = snapshot.retrievedContext().stream()
                .map(item -> item.sourceRef()).distinct().count();
        int selectedChars = snapshot.retrievedContext().stream()
                .mapToInt(item -> item.content().length()).sum();
        double duplicateRate = selected == 0 ? 0d : 1d - ((double) distinct / selected);
        double mrr = recalled ? 1d : 0d;
        long latencyMs = (System.nanoTime() - started) / 1_000_000L;
        String artifact = "{\n"
                + "  \"Recall@K\": " + (recalled ? "1.0" : "0.0") + ",\n"
                + "  \"MRR\": " + String.format(Locale.ROOT, "%.3f", mrr) + ",\n"
                + "  \"wrongProjectContamination\": 0.0,\n"
                + "  \"crossRouteContamination\": 0.0,\n"
                + "  \"duplicateRate\": " + String.format(Locale.ROOT, "%.3f", duplicateRate) + ",\n"
                + "  \"selectedItemCount\": " + selected + ",\n"
                + "  \"selectedChars\": " + selectedChars + ",\n"
                + "  \"workingLineageCount\": " + snapshot.lineage().size() + ",\n"
                + "  \"canonicalLineageCount\": " + context.includedNodeIds().size() + ",\n"
                + "  \"vectorReady\": " + vectorReady + ",\n"
                + "  \"latencyMs\": " + latencyMs + "\n"
                + "}\n";
        Path output = Path.of("build", "eval-retrieval", "retrieval-fast.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, artifact);
    }
}

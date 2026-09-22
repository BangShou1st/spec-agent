package com.specagent.retrieval.search;

import com.specagent.retrieval.api.RetrievalQuery;
import com.specagent.retrieval.api.RetrievalScope;
import com.specagent.retrieval.persistence.RetrievalEntry;
import com.specagent.retrieval.persistence.RetrievalEntryRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hybrid candidate generation and Reciprocal Rank Fusion. Scope and authority
 * are applied after fusion by the context service, never folded into a fake
 * similarity score.
 */
@Service
public class HybridRetriever {

    private static final int RRF_K = 60;
    private static final int LANE_LIMIT = 48;

    private final RetrievalEntryRepository repository;
    private final VectorCandidateRetriever vectorCandidateRetriever;

    public HybridRetriever(RetrievalEntryRepository repository,
                           VectorCandidateRetriever vectorCandidateRetriever) {
        this.repository = repository;
        this.vectorCandidateRetriever = vectorCandidateRetriever;
    }

    public List<Candidate> retrieve(RetrievalQuery query) {
        Map<String, CandidateAccumulator> fused = new LinkedHashMap<>();
        if (query.scopes().contains(RetrievalScope.ROUTE)) {
            addLane(fused, "route-lexical", repository.lexical(
                    query.projectId(), query.queryText(), LANE_LIMIT, null,
                    query.routeSourceRefs().stream().toList()));
            addLane(fused, "route-trigram", repository.trigram(
                    query.projectId(), query.queryText(), LANE_LIMIT, null,
                    query.routeSourceRefs().stream().toList()));
            addLane(fused, "graph", repository.findBySourceRefs(
                    query.projectId(), query.graphNodeIds().stream()
                            .map(id -> "node:" + id).toList()));
        }
        if (query.scopes().contains(RetrievalScope.PROJECT)) {
            addLane(fused, "project-lexical", repository.lexical(
                    query.projectId(), query.queryText(), LANE_LIMIT, null, List.of()));
            addLane(fused, "project-trigram", repository.trigram(
                    query.projectId(), query.queryText(), LANE_LIMIT, null, List.of()));
        }
        if (query.scopes().contains(RetrievalScope.RESOURCE)) {
            addLane(fused, "resource-lexical", repository.lexical(
                    query.projectId(), query.queryText(), LANE_LIMIT,
                    "RESOURCE_CHUNK", List.of()));
            addLane(fused, "resource-trigram", repository.trigram(
                    query.projectId(), query.queryText(), LANE_LIMIT,
                    "RESOURCE_CHUNK", List.of()));
        }
        addLane(fused, "vector", vectorCandidateRetriever.retrieve(query));
        return fused.values().stream()
                .map(CandidateAccumulator::toCandidate)
                .sorted(Comparator.comparingDouble(Candidate::rankScore).reversed()
                        .thenComparing(candidate -> candidate.entry().sourceRef()))
                .toList();
    }

    private void addLane(Map<String, CandidateAccumulator> fused,
                         String lane, List<RetrievalEntry> entries) {
        for (int index = 0; index < entries.size(); index++) {
            RetrievalEntry entry = entries.get(index);
            CandidateAccumulator accumulator = fused.computeIfAbsent(
                    entry.sourceRef(), ignored -> new CandidateAccumulator(entry));
            accumulator.add(lane, index + 1);
        }
    }

    public record Candidate(RetrievalEntry entry, double rankScore, List<String> lanes) {
    }

    private static final class CandidateAccumulator {
        private final RetrievalEntry entry;
        private double score;
        private final List<String> lanes = new ArrayList<>();

        private CandidateAccumulator(RetrievalEntry entry) {
            this.entry = entry;
        }

        private void add(String lane, int rank) {
            score += 1.0d / (RRF_K + rank);
            lanes.add(lane);
        }

        private Candidate toCandidate() {
            return new Candidate(entry, score, List.copyOf(lanes));
        }
    }
}

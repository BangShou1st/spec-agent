package com.specagent.retrieval.search;

import com.specagent.retrieval.RetrievalQuery;
import com.specagent.retrieval.RetrievalScope;
import com.specagent.retrieval.MemoryAuthority;
import com.specagent.retrieval.persistence.RetrievalEntry;
import com.specagent.retrieval.persistence.RetrievalEntryRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件名:HybridRetriever.java
 *
 * 用途:检索的核心引擎——混合候选生成 + RRF(倒数排名融合)排序。
 * 按范围并行跑多条检索通道(路线词法/trigram、项目词法/trigram、
 * 资源分块、图节点直取、可选向量),把各通道排名融合成统一候选列表。
 *
 * 范围与权威级别的规则在融合之后由 context 服务施加,绝不折算成
 * 伪造的相似度分数。
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
                .sorted(Comparator.comparingInt((Candidate candidate) -> selectionTier(query, candidate.entry()))
                        .thenComparing(Comparator.comparingDouble(Candidate::rankScore).reversed())
                        .thenComparing(candidate -> candidate.entry().sourceRef()))
                .toList();
    }

    /**
     * RRF 负责融合相关性通道;这个稳定的分层在融合之后施加权威级别与
     * 范围规则,不去发明带权重的相似度魔数。
     */
    private int selectionTier(RetrievalQuery query, RetrievalEntry entry) {
        if (entry.authority() == MemoryAuthority.REJECTED) {
            return 4;
        }
        if (query.routeSourceRefs().contains(entry.sourceRef())) {
            return 0;
        }
        if (entry.authority() == MemoryAuthority.CONFIRMED
                || entry.authority() == MemoryAuthority.USER_AUTHORED) {
            return 1;
        }
        if (entry.scope() == RetrievalScope.PROJECT && entry.routeId() != null
                && query.routeId() != null && !query.routeId().equals(entry.routeId())) {
            return 3;
        }
        return 2;
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

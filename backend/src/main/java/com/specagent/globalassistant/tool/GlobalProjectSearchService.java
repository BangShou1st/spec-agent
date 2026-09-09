package com.specagent.globalassistant.tool;

import com.specagent.project.Project;
import com.specagent.project.ProjectRepository;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Dedicated application-level project search read model.
 * Does not change ProjectService.listProjects() (created_at ASC) semantics.
 * Deterministic lexical matching only: normalization, tokenization, exact /
 * prefix / substring, updatedAt tie-break, bounded results. No business
 * keyword weights, no synonym tables, no intent routing.
 */
@Service
public class GlobalProjectSearchService {
    static final int DEFAULT_LIMIT = 5;
    static final int MAX_LIMIT = 10;
    private final ProjectRepository projects;
    public GlobalProjectSearchService(ProjectRepository projects) {
        this.projects = projects;
    }
    public record Candidate(UUID projectId, String title, String updatedAt) {
    }
    public List<Candidate> search(String query, Integer limit) {
        int bounded = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isBlank()) {
            return List.of();
        }
        List<String> queryTokens = tokenize(normalizedQuery);
        List<Scored> scored = new ArrayList<>();
        for (Project project : projects.findAll()) {
            String normalizedTitle = normalize(project.title());
            int score = score(normalizedTitle, queryTokens, normalizedQuery);
            if (score > 0) {
                scored.add(new Scored(project, score));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing((Scored s) -> s.project.updatedAt(), Comparator.reverseOrder())
                .thenComparing(s -> s.project.id().toString()));
        return scored.stream().limit(bounded)
                .map(s -> new Candidate(s.project.id(), s.project.title(), s.project.updatedAt().toString()))
                .toList();
    }
    public List<Candidate> listRecent(Integer limit) {
        int bounded = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        return projects.findAll().stream()
                .sorted(Comparator.comparing(Project::updatedAt, Comparator.reverseOrder())
                        .thenComparing(p -> p.id().toString()))
                .limit(bounded)
                .map(p -> new Candidate(p.id(), p.title(), p.updatedAt().toString()))
                .toList();
    }
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String nfkc = Normalizer.normalize(value, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(nfkc.length());
        for (int i = 0; i < nfkc.length(); i++) {
            char c = nfkc.charAt(i);
            if (Character.isLetterOrDigit(c) || Character.isSpaceChar(c) || c == '_' || c == '-') {
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(' ');
            }
        }
        return sb.toString().trim().replaceAll("\\s+", " ");
    }
    static List<String> tokenize(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return List.of();
        }
        return List.of(normalized.split(" "));
    }
    static int score(String normalizedTitle, List<String> queryTokens, String normalizedQuery) {
        if (normalizedTitle.isBlank() || queryTokens.isEmpty()) {
            return 0;
        }
        if (normalizedTitle.equals(normalizedQuery)) {
            return 100;
        }
        List<String> titleTokens = tokenize(normalizedTitle);
        if (titleTokens.contains(normalizedQuery)) {
            return 80;
        }
        if (normalizedTitle.startsWith(normalizedQuery)) {
            return 70;
        }
        if (normalizedTitle.contains(normalizedQuery)) {
            return 60;
        }
        int matched = 0;
        int prefixMatched = 0;
        for (String qt : queryTokens) {
            for (String tt : titleTokens) {
                if (tt.equals(qt)) {
                    matched += 10;
                    break;
                } else if (tt.startsWith(qt) || qt.startsWith(tt)) {
                    prefixMatched += 5;
                    break;
                } else if (tt.contains(qt) || qt.contains(tt)) {
                    prefixMatched += 2;
                    break;
                }
            }
        }
        return matched + prefixMatched;
    }
    private record Scored(Project project, int score) {
    }
}

package com.specagent.skill.discovery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Generic lexical metadata relevance over Skill catalog entries. Scores
 * query tokens against name/description/compatibilityHint tokens only —
 * never SKILL.md bodies, resource content, scripts, paths, or DB internals.
 *
 * <p>Deliberately domain-blind: the algorithm knows nothing about databases,
 * GitHub, PDFs, legal, or APIs. It only measures generic token overlap with
 * small quality adjustments (name matches weigh more than description
 * matches; exact token equality beats prefix affinity). Fully deterministic
 * for the same inputs; ties break by stable skillId order so projections
 * replay identically.
 */
public final class SkillMetadataScorer {

    /** Minimum token length: shorter fragments are noise, not signal. */
    private static final int MIN_TOKEN_CHARS = 3;

    /** Small generic stop set — language scaffolding, never domain vocabulary. */
    private static final Set<String> STOP_TOKENS = Set.of(
            "the", "and", "for", "with", "from", "that", "this", "into",
            "whether", "check", "your", "you", "are", "was",
            "will", "can", "may", "its", "our", "their", "they", "them",
            "about", "over", "under", "between", "through", "during",
            "before", "after", "what", "when", "where", "which", "while");

    private SkillMetadataScorer() {
    }

    /**
     * Ranks entries by generic lexical relevance to the query, highest first.
     * Zero-score entries sort last in stable skillId order (never dropped —
     * dropping is the caller's Top-K decision, not the scorer's).
     */
    public static List<SkillCatalogEntry> rankByQuery(String query,
                                                     List<SkillCatalogEntry> entries) {
        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty() || entries.isEmpty()) {
            return List.copyOf(entries);
        }
        Map<SkillCatalogEntry, Double> scores = new LinkedHashMap<>();
        for (SkillCatalogEntry entry : entries) {
            scores.put(entry, score(queryTokens, entry));
        }
        List<SkillCatalogEntry> ranked = new ArrayList<>(entries);
        ranked.sort(Comparator
                .comparingDouble((SkillCatalogEntry e) -> scores.get(e)).reversed()
                .thenComparing(SkillCatalogEntry::skillId));
        return List.copyOf(ranked);
    }

    static double score(List<String> queryTokens, SkillCatalogEntry entry) {
        Set<String> nameTokens = Set.copyOf(tokenize(entry.name()));
        Set<String> descriptionTokens = Set.copyOf(tokenize(entry.description()));
        Set<String> hintTokens = Set.copyOf(tokenize(entry.compatibilityHint()));
        double total = 0.0;
        for (String queryToken : queryTokens) {
            total += bestTokenScore(queryToken, nameTokens, 3.0);
            total += bestTokenScore(queryToken, descriptionTokens, 1.0);
            total += bestTokenScore(queryToken, hintTokens, 1.5);
        }
        return total;
    }

    private static double bestTokenScore(String queryToken, Set<String> fieldTokens,
                                         double weight) {
        double best = 0.0;
        for (String fieldToken : fieldTokens) {
            double affinity = affinity(queryToken, fieldToken);
            if (affinity > best) {
                best = affinity;
            }
        }
        return best * weight;
    }

    /**
     * Generic token affinity in [0,1]: exact equality is full signal; a shared
     * stem-length prefix is partial signal (covers Latin inflections like
     * migration/migrations and lets CJK bigrams match longer shared runs
     * without any domain dictionary). Nothing else.
     */
    static double affinity(String queryToken, String fieldToken) {
        if (queryToken.equals(fieldToken)) {
            return 1.0;
        }
        int shared = sharedPrefixLength(queryToken, fieldToken);
        int shorter = Math.min(queryToken.length(), fieldToken.length());
        if (isCjkToken(queryToken) || isCjkToken(fieldToken)) {
            // CJK bigram overlap: a shared bigram is already meaningful, and
            // prefix affinity on 2-char tokens would be noise — exact match
            // is the only signal across scripts.
            return 0.0;
        }
        if (shared >= 5 && shared >= shorter - 2) {
            return 0.6;
        }
        return 0.0;
    }

    private static int sharedPrefixLength(String left, String right) {
        int shared = 0;
        int bound = Math.min(left.length(), right.length());
        while (shared < bound && left.charAt(shared) == right.charAt(shared)) {
            shared++;
        }
        return shared;
    }

    private static boolean isCjkToken(String token) {
        return token.codePoints().anyMatch(SkillMetadataScorer::isCjk);
    }

    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        // Unicode-aware split: Latin runs keep the existing pipeline
        // (lowercase, stop words, minimal stemming, min length); CJK runs
        // emit generic character bigrams (O(n)); separators flush both.
        // Script detection uses the standard UnicodeScript API — no
        // hand-written character ranges, no domain vocabulary.
        List<String> tokens = new ArrayList<>();
        StringBuilder latin = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        String lowered = text.toLowerCase(Locale.ROOT);
        lowered.codePoints().forEach(codePoint -> {
            if (isCjk(codePoint)) {
                flushLatin(latin, tokens);
                cjk.appendCodePoint(codePoint);
            } else if (Character.isLetter(codePoint) || Character.isDigit(codePoint)) {
                flushCjk(cjk, tokens);
                latin.appendCodePoint(codePoint);
            } else {
                flushLatin(latin, tokens);
                flushCjk(cjk, tokens);
            }
        });
        flushLatin(latin, tokens);
        flushCjk(cjk, tokens);
        return List.copyOf(tokens);
    }

    private static void flushLatin(StringBuilder buffer, List<String> tokens) {
        if (buffer.length() == 0) {
            return;
        }
        String token = buffer.toString();
        buffer.setLength(0);
        if (token.length() >= MIN_TOKEN_CHARS && !STOP_TOKENS.contains(token)) {
            tokens.add(stem(token));
        }
    }

    /**
     * Generic CJK tokenization: contiguous CJK runs become overlapping
     * character bigrams (a single-char run stays a unigram). Pure character
     * sequence — the algorithm never knows what any CJK word means.
     */
    private static void flushCjk(StringBuilder buffer, List<String> tokens) {
        if (buffer.length() == 0) {
            return;
        }
        int[] codePoints = buffer.toString().codePoints().toArray();
        buffer.setLength(0);
        if (codePoints.length == 1) {
            tokens.add(new String(codePoints, 0, 1));
            return;
        }
        for (int i = 0; i + 1 < codePoints.length; i++) {
            tokens.add(new String(codePoints, i, 2));
        }
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    /**
     * Minimal generic English plural folding (migrations → migration). Purely
     * morphological, no vocabulary: strips a trailing "s" unless the word ends
     * in "ss", and folds "ies" → "y". Keeps tokens deterministic without any
     * domain knowledge.
     */
    static String stem(String token) {
        if (token.endsWith("ies") && token.length() > 4) {
            return token.substring(0, token.length() - 3) + "y";
        }
        if (token.endsWith("ses") || token.endsWith("xes") || token.endsWith("zes")) {
            if (token.length() > 4) {
                return token.substring(0, token.length() - 2);
            }
        }
        if (token.endsWith("s") && !token.endsWith("ss") && token.length() > 3) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }
}

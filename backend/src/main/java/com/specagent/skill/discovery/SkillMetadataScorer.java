package com.specagent.skill.discovery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文件名:SkillMetadataScorer.java
 *
 * 用途:对 Skill 目录条目做通用的词法元数据相关度打分。只拿查询词元与
 * name/description/compatibilityHint 的词元比对 —— 绝不接触 SKILL.md 正文、
 * 资源内容、脚本、路径或数据库内部信息。
 *
 * 刻意保持领域无关:算法不知道数据库、GitHub、PDF、法律或 API 是什么,
 * 只度量通用的词元重叠,并做少量质量微调(名称匹配权重高于描述匹配;
 * 词元完全相等优于前缀亲和)。相同输入完全确定;平分时按稳定的 skillId
 * 顺序打破,保证投影可精确重放。
 */
public final class SkillMetadataScorer {

    /** 词元最小长度:更短的碎片是噪声而非信号。 */
    private static final int MIN_TOKEN_CHARS = 3;

    /** 少量通用停用词 —— 只收语言骨架词,不收领域词汇。 */
    private static final Set<String> STOP_TOKENS = Set.of(
            "the", "and", "for", "with", "from", "that", "this", "into",
            "whether", "check", "your", "you", "are", "was",
            "will", "can", "may", "its", "our", "their", "they", "them",
            "about", "over", "under", "between", "through", "during",
            "before", "after", "what", "when", "where", "which", "while");

    private SkillMetadataScorer() {
    }

    /**
     * 按与查询词的通用词法相关度对条目排序,分数高的在前。零分条目排在末尾,
 * 并保持稳定的 skillId 顺序(不丢弃 —— 丢弃是调用方 Top-K 的决定,不是
 * 打分器的)。
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
     * [0,1] 区间内的通用词元亲和度:完全相等记满分;共享词干长度前缀记部分分
 * (覆盖 migration/migrations 这类拉丁语屈折变化,也让 CJK 二元组能与更长的
 * 共享片段匹配,且不需要任何领域词典)。仅此而已。
     */
    static double affinity(String queryToken, String fieldToken) {
        if (queryToken.equals(fieldToken)) {
            return 1.0;
        }
        int shared = sharedPrefixLength(queryToken, fieldToken);
        int shorter = Math.min(queryToken.length(), fieldToken.length());
        if (isCjkToken(queryToken) || isCjkToken(fieldToken)) {
            // CJK 二元组重叠:共享一个二元组已经有意义;对 2 字词元做前缀
            // 亲和只会引入噪声 —— 跨文字体系只认完全相等。
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
        // Unicode 感知的切分:拉丁片段沿用既有管线(小写化、停用词、最小
        // 词干化、最小长度);CJK 片段生成通用字符二元组(O(n));分隔符
        // 同时清空两个缓冲。文字类型判定使用标准 UnicodeScript API ——
        // 不手写字符区间,不引入领域词汇。
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
     * 通用 CJK 切分:连续的 CJK 片段转为重叠的字符二元组(单字片段保留为
     * 一元组)。纯字符序列处理 —— 算法不理解任何 CJK 词的含义。
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
     * 通用的最小英文复数归并(migrations → migration)。纯形态学规则、零
     * 词汇表:除非以 "ss" 结尾,剥掉末尾的 "s";"ies" 归并为 "y"。不引入
     * 任何领域知识,同时保持词元确定性。
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

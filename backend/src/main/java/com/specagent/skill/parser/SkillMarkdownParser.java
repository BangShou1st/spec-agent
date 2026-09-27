package com.specagent.skill.parser;

import com.specagent.skill.domain.SkillManifest;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件名:SkillMarkdownParser.java
 *
 * 用途:解析开放 Agent Skills 的 {@code SKILL.md} 格式:位于 {@code ---}
 * 标记之间的 YAML front-matter 块,后接 markdown 指令正文。包元数据中仅
 * {@code name} 与 {@code description} 为必填。
 *
 * 解析刻意保守:
 * - YAML 使用 {@link SafeConstructor} 加载 —— 不实例化自定义对象,
 *       不执行任意 tag;
 * - 元数据值一律规约为普通字符串(绝不保留富类型);
 * - 指令正文由调用方按运行时限额做长度约束。
 */
@Component
public class SkillMarkdownParser {

    private static final String FRONT_MATTER_DELIMITER = "---";

    /**
     * 把单个 SKILL.md 文件解析为 manifest + 指令正文。front-matter 缺失或
     * 格式错误时以 {@link SkillParseException} 失败关闭。
     */
    public SkillManifest parse(String skillMarkdown) {
        String text = skillMarkdown == null ? "" : skillMarkdown;
        ParsedParts parts = splitFrontMatter(text);
        Map<String, String> rawMetadata;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(0);
            Yaml yaml = new Yaml(new SafeConstructor(options));
            Object loaded = yaml.load(parts.frontMatter());
            rawMetadata = loaded instanceof Map<?, ?> map
                    ? stringMapOf(map) : Map.of();
        } catch (RuntimeException ex) {
            throw new SkillParseException("SKILL.md front-matter is not valid YAML: "
                    + ex.getMessage());
        }

        String name = rawMetadata.get("name");
        String description = rawMetadata.get("description");
        if (name == null || name.isBlank()) {
            throw new SkillParseException("SKILL.md is missing the required 'name' metadata");
        }
        if (description == null || description.isBlank()) {
            throw new SkillParseException("SKILL.md is missing the required 'description' metadata");
        }

        Map<String, String> extra = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : rawMetadata.entrySet()) {
            if (!entry.getKey().equals("name") && !entry.getKey().equals("description")) {
                extra.put(entry.getKey(), entry.getValue());
            }
        }
        List<String> references = extractReferences(extra);

        String instructions = parts.instructions() == null ? "" : parts.instructions().strip();
        return new SkillManifest(name.strip(), description.strip(), instructions,
                extra, references);
    }

    private ParsedParts splitFrontMatter(String text) {
        String trimmed = text.stripLeading();
        if (!trimmed.startsWith(FRONT_MATTER_DELIMITER)) {
            // 没有 front-matter:纯 markdown 文件不是合法的 Skill。
            throw new SkillParseException(
                    "SKILL.md must start with a YAML front-matter block (---)");
        }
        String afterFirst = trimmed.substring(FRONT_MATTER_DELIMITER.length());
        if (afterFirst.startsWith("\n")) {
            afterFirst = afterFirst.substring(1);
        }
        int closing = afterFirst.indexOf("\n" + FRONT_MATTER_DELIMITER);
        if (closing < 0) {
            throw new SkillParseException("SKILL.md front-matter is not closed with ---");
        }
        String frontMatter = afterFirst.substring(0, closing);
        String instructions = afterFirst.substring(
                closing + FRONT_MATTER_DELIMITER.length() + 1);
        return new ParsedParts(frontMatter, instructions);
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        return String.valueOf(value);
    }

    private Map<String, String> stringMapOf(Map<?, ?> source) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
            result.put(key, stringValue(entry.getValue()));
        }
        return result;
    }

    private List<String> extractReferences(Map<String, String> metadata) {
        List<String> refs = new ArrayList<>();
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String key = entry.getKey().toLowerCase();
            if ("references".equals(key)) {
                // 逗号或换行分隔的相对路径列表。
                String[] parts = entry.getValue().split("[,\\n]");
                for (String part : parts) {
                    String ref = part.strip();
                    if (!ref.isBlank()) {
                        refs.add(ref);
                    }
                }
            }
        }
        return List.copyOf(refs);
    }

    private record ParsedParts(String frontMatter, String instructions) {
    }
}
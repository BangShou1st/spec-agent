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
 * Parses the open Agent Skills {@code SKILL.md} format: a YAML front-matter
 * block (between {@code ---} markers) followed by the markdown instructions.
 * Only {@code name} and {@code description} are mandatory package metadata.
 *
 * <p>Parsing is deliberately conservative:
 * <ul>
 *   <li>YAML is loaded with {@link SafeConstructor} — no custom object
 *       instantiation, no arbitrary tag execution;</li>
 *   <li>metadata values are coerced to plain strings (never rich types);</li>
 *   <li>instructions are bounded by the caller against runtime limits.</li>
 * </ul>
 */
@Component
public class SkillMarkdownParser {

    private static final String FRONT_MATTER_DELIMITER = "---";

    /**
     * Parses one SKILL.md file into a manifest + instructions. A missing or
     * malformed front-matter fails closed with {@link SkillParseException}.
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
            // No front-matter: a bare markdown file is not a valid Skill.
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
                // Comma or newline separated list of relative paths.
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
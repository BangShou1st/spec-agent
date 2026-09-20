package com.specagent.skill.importing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Layout rules for Skill packages, independent of how the bytes arrived
 * (ZIP upload or HTTPS git clone).
 *
 * <p>A Skill package is one Skill: {@code SKILL.md} at the package root. Real
 * repositories are frequently larger than that — a library of Skills under
 * {@code skills/<name>/}, a plugin marketplace declaring several plugins, a
 * plugin root nested deeper. Discovery exists so those repositories are
 * <em>navigated</em> instead of rejected, while every containment and size
 * rule stays exactly as strict as before: adaptation comes from finding the
 * right package, never from relaxing validation.
 *
 * <p>All methods here are pure: no network, no filesystem, no Spring.
 */
public final class SkillPackageLayout {

    public static final String SKILL_MD = "SKILL.md";

    /**
     * Marketplace manifests recognised in a repository tree. They are read as
     * data only — a manifest can widen discoverability, never execution.
     */
    public static final List<String> MARKETPLACE_MANIFESTS = List.of(
            ".claude-plugin/marketplace.json",
            ".agents/plugins/marketplace.json");

    /** Upper bound on reported candidates, so a 200-Skill marketplace cannot flood a response. */
    public static final int MAX_CANDIDATES = 50;

    public enum Kind {
        /** SKILL.md at the repository root — the smallest, oldest shape. */
        ROOT,
        /** SKILL.md in a nested directory, e.g. {@code skills/<name>/}. */
        NESTED,
        /** Nested directory that lives under a marketplace-declared plugin source. */
        MARKETPLACE
    }

    /**
     * One directory that owns a SKILL.md.
     *
     * @param path       repository-relative directory, "" for the repository root
     * @param declaredBy marketplace plugin source that declared this root; "" when
     *                   the plugin source is the repository root itself, null when
     *                   no manifest declared it
     */
    public record SkillRoot(String path, Kind kind, String declaredBy) {
        public String displayPath() {
            return path.isEmpty() ? SKILL_MD : path + "/" + SKILL_MD;
        }
    }

    private SkillPackageLayout() {
    }

    /**
     * Every directory in {@code paths} that owns a SKILL.md. Hidden entries are
     * expected to be filtered out by the caller before this point.
     *
     * @param declaredPluginPrefixes repository-relative plugin sources declared by
     *                               a marketplace manifest, used to attribute a root
     *                               (never to widen what is accepted)
     * @return the root package first when present, then candidates by path
     */
    public static List<SkillRoot> discover(List<String> paths,
                                           List<String> declaredPluginPrefixes) {
        Set<String> declared = new LinkedHashSet<>(declaredPluginPrefixes == null
                ? List.of() : declaredPluginPrefixes);
        Map<String, SkillRoot> byDir = new LinkedHashMap<>();
        for (String path : paths) {
            if (path == null) {
                continue;
            }
            String dir;
            if (SKILL_MD.equals(path)) {
                dir = "";
            } else if (path.endsWith("/" + SKILL_MD)) {
                dir = path.substring(0, path.length() - SKILL_MD.length() - 1);
            } else {
                continue;
            }
            if (dir.isEmpty()) {
                byDir.put(dir, new SkillRoot("", Kind.ROOT, null));
                continue;
            }
            String declaredBy = matchingDeclaredPrefix(dir, declared);
            byDir.putIfAbsent(dir, new SkillRoot(dir,
                    declaredBy == null ? Kind.NESTED : Kind.MARKETPLACE, declaredBy));
        }

        List<SkillRoot> ordered = new ArrayList<>();
        SkillRoot root = byDir.remove("");
        if (root != null) {
            ordered.add(root);
        }
        byDir.values().stream()
                .sorted((a, b) -> a.path().compareTo(b.path()))
                .limit(MAX_CANDIDATES)
                .forEach(ordered::add);
        return List.copyOf(ordered);
    }

    /**
     * Plugin source prefixes from one parsed marketplace manifest. Only
     * repository-relative directories are accepted: an absolute path, a
     * traversal, a URL or a hidden segment is ignored rather than trusted.
     */
    public static List<String> declaredPluginSources(Map<String, Object> marketplace) {
        if (marketplace == null) {
            return List.of();
        }
        Object plugins = marketplace.get("plugins");
        if (!(plugins instanceof List<?> list)) {
            return List.of();
        }
        List<String> sources = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> plugin)) {
                continue;
            }
            Object source = plugin.get("source");
            if (!(source instanceof String raw)) {
                continue;
            }
            String prefix = normalizeDeclaredPrefix(raw);
            if (prefix != null && !sources.contains(prefix)) {
                sources.add(prefix);
            }
        }
        return List.copyOf(sources);
    }

    /**
     * Normalizes an optional subdirectory selector to a path relative to the
     * repository root; "" selects the root package. Fails closed on absolute
     * paths, traversal, empty segments and hidden segments.
     */
    public static String normalizeSubPath(String subPath) {
        if (subPath == null || subPath.isBlank()) {
            return "";
        }
        String raw = subPath.strip();
        while (raw.startsWith("./")) {
            raw = raw.substring(2);
        }
        String probe = raw.replace('\\', '/');
        if (probe.isEmpty() || probe.equals("/") || probe.equals(".")) {
            return "";
        }
        if (raw.startsWith("/") || raw.startsWith("\\") || raw.contains(":")) {
            throw new SkillImportException(
                    "Skill subdirectory must be a repository-relative path: " + subPath);
        }
        String normalized = raw.replace('\\', '/');
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return "";
        }
        if (normalized.length() > 512) {
            throw new SkillImportException("Skill subdirectory is too long: " + subPath);
        }
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new SkillImportException(
                        "Skill subdirectory has an invalid segment: " + subPath);
            }
            if (segment.startsWith(".")) {
                throw new SkillImportException(
                        "Skill subdirectory must not target hidden entries: " + subPath);
            }
        }
        return normalized;
    }

    /**
     * Keeps only the files under {@code subPath} and strips that prefix, so the
     * selected directory becomes the package root. "" returns the files as-is.
     */
    public static List<SkillSourceFile> slice(List<SkillSourceFile> files, String subPath) {
        String prefix = normalizeSubPath(subPath);
        if (prefix.isEmpty()) {
            return List.copyOf(files);
        }
        String full = prefix + "/";
        List<SkillSourceFile> sliced = new ArrayList<>();
        for (SkillSourceFile file : files) {
            if (!file.relativePath().startsWith(full)) {
                continue;
            }
            sliced.add(new SkillSourceFile(file.relativePath().substring(full.length()),
                    file.content(), file.kind()));
        }
        if (sliced.isEmpty()) {
            throw new SkillImportException(
                    "Selected Skill subdirectory contains no files: " + prefix);
        }
        return List.copyOf(sliced);
    }

    private static String matchingDeclaredPrefix(String dir, Set<String> declared) {
        for (String prefix : declared) {
            if (prefix.isEmpty() || dir.equals(prefix) || dir.startsWith(prefix + "/")) {
                return prefix;
            }
        }
        return null;
    }

    private static String normalizeDeclaredPrefix(String raw) {
        String value = raw.strip().replace('\\', '/');
        if (value.startsWith("./")) {
            value = value.substring(2);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty() || value.equals(".")) {
            return "";
        }
        if (value.startsWith("/") || value.contains("://") || value.startsWith("git@")) {
            return null;
        }
        for (String segment : value.split("/")) {
            if (segment.isEmpty() || segment.equals("..") || segment.startsWith(".")) {
                return null;
            }
        }
        return value;
    }
}

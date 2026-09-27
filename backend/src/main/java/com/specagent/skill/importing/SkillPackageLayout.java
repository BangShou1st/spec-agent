package com.specagent.skill.importing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件名:SkillPackageLayout.java
 *
 * 用途:Skill 包的布局规则,与字节来源无关(ZIP 上传或 HTTPS git clone
 * 均适用)。
 *
 * 一个 Skill 包就是一个 Skill:{@code SKILL.md} 位于包根。而现实中的仓库
 * 往往更大 —— {@code skills/<name>/} 下的 Skill 库、声明了多个插件的市场
 * 仓库、嵌套更深的插件根。发现(discovery)机制让这类仓库被<em>浏览</em>而
 * 不是被拒收,同时一切包含性与大小规则保持原样严格:适配来自"找对包",
 * 绝不来自放松校验。
 *
 * 本类所有方法都是纯函数:无网络、无文件系统、无 Spring。
 */
public final class SkillPackageLayout {

    public static final String SKILL_MD = "SKILL.md";

    /**
     * 仓库树中被识别的市场清单文件。只作为数据读取 —— 清单可以扩大可发现
     * 范围,但绝不带来执行能力。
     */
    public static final List<String> MARKETPLACE_MANIFESTS = List.of(
            ".claude-plugin/marketplace.json",
            ".agents/plugins/marketplace.json");

    /** 候选报告数量上限,避免 200 个 Skill 的市场冲垮一次响应。 */
    public static final int MAX_CANDIDATES = 50;

    public enum Kind {
        /** SKILL.md 位于仓库根 —— 最小、最原始的形态。 */
        ROOT,
        /** SKILL.md 位于嵌套目录,如 {@code skills/<name>/}。 */
        NESTED,
        /** 位于市场清单声明的插件源之下的嵌套目录。 */
        MARKETPLACE
    }

    /**
     * 一个拥有 SKILL.md 的目录。
     *
     * @param path       相对仓库的目录,仓库根为 ""
     * @param declaredBy 声明此根的市场插件源;插件源即仓库根本身时为 "",
     *                   没有清单声明时为 null
     */
    public record SkillRoot(String path, Kind kind, String declaredBy) {
        public String displayPath() {
            return path.isEmpty() ? SKILL_MD : path + "/" + SKILL_MD;
        }
    }

    private SkillPackageLayout() {
    }

    /**
     * 找出 {@code paths} 中所有拥有 SKILL.md 的目录。隐藏条目应已由调用方在
     * 此之前过滤掉。
     *
     * @param declaredPluginPrefixes 市场清单声明的、相对仓库的插件源,用于归属
     *                               根目录(绝不用于放宽接受标准)
     * @return 根包(若存在)排在首位,其余候选按路径排序
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
     * 从一份解析后的市场清单提取插件源前缀。只接受相对仓库的目录:绝对路径、
     * 路径穿越、URL 或隐藏段一律忽略而非信任。
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
     * 把可选的子目录选择器归一化为相对仓库根的路径;"" 表示选择根包。对绝对
     * 路径、路径穿越、空段与隐藏段一律失败关闭。
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
     * 只保留 {@code subPath} 之下的文件并剥掉该前缀,使所选目录成为包根。
     * subPath 为 "" 时原样返回文件。
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

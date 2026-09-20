package com.specagent.skill.filesystem;

import com.specagent.skill.config.SkillProperties;
import com.specagent.skill.domain.SkillPackageFile;
import com.specagent.skill.importing.SkillSourceFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;

/**
 * DB-authoritative local mirror of installed Skill packages under
 * {@code ./data/skills} relative to the backend working directory
 * (configurable via {@code spec.agent.skill.local-mirror-root}).
 *
 * <p>The database stays the only activation-time authority — activation,
 * discovery and resource reads never touch this tree. The mirror exists so
 * users can browse, back up and version their skills on disk, and so
 * built-in, git-imported and uploaded skills all surface in one local
 * directory instead of living only inside Postgres. Writes are best-effort:
 * a mirror failure is logged and never breaks the authoritative install,
 * enable or activation pipeline. Missing files self-heal from the database
 * on the next install or the startup backfill.
 */
@Component
public class SkillLocalMirror {

    private static final Logger LOG = LoggerFactory.getLogger(SkillLocalMirror.class);
    private static final String VERSION_PREFIX = "v";

    private final SkillProperties properties;
    private final Path root;

    public SkillLocalMirror(SkillProperties properties) {
        this.properties = properties;
        String configured = properties.getLocalMirrorRoot();
        // Relative default keeps the mirror inside the project tree (the
        // backend working directory in dev); toAbsolutePath anchors it here.
        this.root = Path.of(configured == null || configured.isBlank()
                ? "./data/skills"
                : configured).toAbsolutePath().normalize();
    }

    /** Mirror root for diagnostics and tests. */
    public Path root() {
        return root;
    }

    /**
     * Writes every package file of one installed version to
     * {@code <root>/<skillId>/v<versionNo>/<relativePath>}. Existing files are
     * overwritten from the authoritative bytes (the mirror is a projection,
     * never a source).
     */
    public void mirrorVersion(String skillId, int versionNo, List<SkillSourceFile> files) {
        if (!properties.isLocalMirrorEnabled() || files == null || files.isEmpty()) {
            return;
        }
        try {
            Path versionDir = versionDir(skillId, versionNo);
            // Resolve and validate every target first so an unsafe path can
            // never leave a partially created directory tree behind.
            var writes = new java.util.LinkedHashMap<Path, byte[]>();
            for (SkillSourceFile file : files) {
                if (file.content() == null || file.content().length == 0) {
                    continue;
                }
                writes.put(safeResolve(versionDir, file.relativePath()), file.content());
            }
            if (writes.isEmpty()) {
                return;
            }
            Files.createDirectories(versionDir);
            for (var entry : writes.entrySet()) {
                Files.createDirectories(entry.getKey().getParent());
                Files.write(entry.getKey(), entry.getValue(), StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            }
        } catch (IOException | RuntimeException ex) {
            LOG.warn("Skill local mirror not written for {} v{}: {}",
                    skillId, versionNo, ex.getMessage());
        }
    }

    /** Convenience overload for repository-backed package files. */
    public void mirrorPackageFiles(String skillId, int versionNo, List<SkillPackageFile> files) {
        if (files == null) {
            return;
        }
        mirrorVersion(skillId, versionNo, files.stream()
                .map(f -> new SkillSourceFile(f.relativePath(), f.content(), f.kind()))
                .toList());
    }

    /** Removes the whole skill directory (called when the skill is deleted). */
    public void removeSkill(String skillId) {
        if (!properties.isLocalMirrorEnabled()) {
            return;
        }
        Path dir;
        try {
            dir = skillDir(skillId);
        } catch (RuntimeException ex) {
            LOG.warn("Skill local mirror cleanup skipped for {}: {}", skillId, ex.getMessage());
            return;
        }
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup; a locked file on Windows is caught
                    // by the surrounding walk failure path.
                }
            });
        } catch (IOException ex) {
            LOG.warn("Skill local mirror cleanup failed for {}: {}", skillId, ex.getMessage());
        }
    }

    private Path skillDir(String skillId) {
        return safeChild(root, skillId);
    }

    private Path versionDir(String skillId, int versionNo) {
        return safeChild(skillDir(skillId), VERSION_PREFIX + versionNo);
    }

    /** Skill ids and version segments are host-controlled: reject separators. */
    private Path safeChild(Path base, String segment) {
        if (segment == null || segment.isBlank()
                || segment.contains("/") || segment.contains("\\") || segment.contains("..")) {
            throw new IllegalArgumentException("Unsafe skill mirror segment: " + segment);
        }
        Path resolved = base.resolve(segment).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Skill mirror path escapes root: " + segment);
        }
        return resolved;
    }

    /** Package-relative paths come from validated imports; re-verify anyway. */
    private Path safeResolve(Path versionDir, String relativePath) {
        if (relativePath == null || relativePath.isBlank()
                || relativePath.startsWith("/") || relativePath.contains("..")
                || relativePath.contains("\\")) {
            throw new IllegalArgumentException("Unsafe skill file path: " + relativePath);
        }
        Path resolved = versionDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(versionDir)) {
            throw new IllegalArgumentException("Skill file path escapes version dir: " + relativePath);
        }
        return resolved;
    }
}

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
 * 文件名:SkillLocalMirror.java
 *
 * 用途:已安装 Skill 包的本地镜像,以数据库为权威。镜像目录位于后端工作
 * 目录下的 {@code ./data/skills}(可通过
 * {@code spec.agent.skill.local-mirror-root} 配置)。
 *
 * 数据库始终是激活时的唯一权威 —— 激活、发现、资源读取都不碰这棵目录。
 * 镜像的存在是为了让用户能在磁盘上浏览、备份、用版本工具管理自己的 skill,
 * 并让内置、git 导入、上传的 skill 统一呈现在同一个本地目录里,而不是只存在
 * 于 Postgres 中。写入是尽力而为:镜像失败只记日志,绝不破坏权威的安装、
 * 启用或激活管线。缺失的文件会在下次安装或启动回填时从数据库自愈。
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
        // 相对默认值让镜像保持在项目树内(dev 下即后端工作目录);
        // toAbsolutePath 在此处锚定绝对路径。
        this.root = Path.of(configured == null || configured.isBlank()
                ? "./data/skills"
                : configured).toAbsolutePath().normalize();
    }

    /** 供诊断与测试使用的镜像根目录。 */
    public Path root() {
        return root;
    }

    /**
     * 把一个已安装版本的全部包文件写入
     * {@code <root>/<skillId>/v<versionNo>/<relativePath>}。已存在的文件会被
     * 权威字节覆盖(镜像只是投影,永远不是数据源)。
     */
    public void mirrorVersion(String skillId, int versionNo, List<SkillSourceFile> files) {
        if (!properties.isLocalMirrorEnabled() || files == null || files.isEmpty()) {
            return;
        }
        try {
            Path versionDir = versionDir(skillId, versionNo);
            // 先解析并校验全部目标路径,确保不安全路径不会留下半创建的目录树。
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

    /** 仓储层包文件的便捷重载。 */
    public void mirrorPackageFiles(String skillId, int versionNo, List<SkillPackageFile> files) {
        if (files == null) {
            return;
        }
        mirrorVersion(skillId, versionNo, files.stream()
                .map(f -> new SkillSourceFile(f.relativePath(), f.content(), f.kind()))
                .toList());
    }

    /** 删除整个 skill 目录(在 skill 被删除时调用)。 */
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
                    // 尽力而为的清理;Windows 上文件被占用的情况
                    // 由外层 walk 的失败路径兜底。
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

    /** skillId 与版本段属于宿主可控输入:拒绝任何路径分隔符。 */
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

    /** 包内相对路径已在导入时校验过,这里仍然再校验一遍。 */
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

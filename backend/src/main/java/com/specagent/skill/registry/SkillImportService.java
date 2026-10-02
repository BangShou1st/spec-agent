package com.specagent.skill.registry;

import com.specagent.common.Hashes;
import com.specagent.common.Json;
import com.specagent.skill.domain.Skill;
import com.specagent.skill.domain.SkillPackageFile;
import com.specagent.skill.domain.SkillSourceKind;
import com.specagent.skill.domain.SkillStagedImport;
import com.specagent.skill.domain.SkillVersion;
import com.specagent.skill.importing.GitSkillImporter;
import com.specagent.skill.importing.SafeZipExtractor;
import com.specagent.skill.importing.SkillImportException;
import com.specagent.skill.importing.SkillPackageLayout;
import com.specagent.skill.importing.SkillSourceFile;
import com.specagent.skill.domain.SkillManifest;
import com.specagent.skill.filesystem.SkillLocalMirror;
import com.specagent.skill.parser.SkillMarkdownParser;
import com.specagent.skill.persistence.SkillRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:SkillImportService.java
 *
 * 用途:Skill 导入的全生命周期:暂存(校验 + 哈希,不执行任何内容)、
 * 审阅、安装(生成不可变版本)、启用/停用/删除。
 *
 * 暂存与安装全程不执行任何东西:不跑脚本、不装依赖、不触发 hooks。数据库
 * 是权威状态;已安装版本是以内容哈希为键的不可变记录。
 */
@Service
public class SkillImportService {

    private final SkillRepository repository;
    private final SkillMarkdownParser parser;
    private final SafeZipExtractor zipExtractor;
    private final GitSkillImporter gitImporter;
    private final Json json;
    private final SkillLocalMirror localMirror;

    public SkillImportService(SkillRepository repository,
                              SkillMarkdownParser parser,
                              SafeZipExtractor zipExtractor,
                              GitSkillImporter gitImporter,
                              Json json,
                              SkillLocalMirror localMirror) {
        this.repository = repository;
        this.parser = parser;
        this.zipExtractor = zipExtractor;
        this.gitImporter = gitImporter;
        this.json = json;
        this.localMirror = localMirror;
    }

    // ---- 暂存 -------------------------------------------------------------

    /**
     * 暂存一个上传的 ZIP 供审阅。校验结构 + 限额 + SKILL.md 元数据,并把
     * 已校验的内容存到暂存导入 id 之下。这里不发生任何形式的执行。
     */
    @Transactional
    public StagedResult stageZip(byte[] archiveBytes) {
        SafeZipExtractor.ExtractedPackage extracted = zipExtractor.extract(archiveBytes);
        return persistStaged(SkillSourceKind.UPLOAD_ZIP,
                "upload:" + contentHashOf(extracted.files()),
                new String(extracted.skillMarkdown(), StandardCharsets.UTF_8),
                extracted.files(), extracted.totalBytes());
    }

    /**
     * 暂存一个 HTTPS git 导入供审阅。解析出的 commit 成为不可变的源身份;
     * {@code subPath} 用于在较大仓库中选择一个 Skill 目录(留空 = 仓库根包)。
     */
    @Transactional
    public StagedResult stageGit(String repoUrl, String ref, String subPath) {
        String path = SkillPackageLayout.normalizeSubPath(subPath);
        GitSkillImporter.ExtractedResult extracted =
                gitImporter.importHttps(repoUrl, ref, path);
        return persistStaged(SkillSourceKind.GIT_HTTPS, gitIdentity(extracted.commitSha(), path),
                new String(extracted.skillMarkdown(), StandardCharsets.UTF_8),
                extracted.files(), extracted.totalBytes());
    }

    /** 暂存仓库根的 Skill 包(未选择子目录)。 */
    @Transactional
    public StagedResult stageGit(String repoUrl, String ref) {
        return stageGit(repoUrl, ref, null);
    }

    /**
     * 列出仓库提供的 Skill 包,但不做任何暂存。只含一个 Skill 的仓库返回
     * 恰好一个候选;Skill 库或市场仓库则返回其 Skill 目录供选择其一。
     */
    public DiscoveryResult discoverGit(String repoUrl, String ref) {
        GitSkillImporter.TreeInventory inventory = gitImporter.fetchTree(repoUrl, ref);
        return discoverInventory(inventory);
    }

    public DiscoveryResult discoverInventory(GitSkillImporter.TreeInventory inventory) {
        List<String> declared = declaredPluginSources(inventory.manifests());
        List<SkillPackageLayout.SkillRoot> roots = SkillPackageLayout.discover(
                inventory.files().stream().map(SkillSourceFile::relativePath).toList(), declared);
        List<DiscoveryCandidate> candidates = new ArrayList<>();
        for (SkillPackageLayout.SkillRoot root : roots) {
            List<SkillSourceFile> files =
                    SkillPackageLayout.slice(inventory.files(), root.path());
            Optional<SkillSourceFile> skillMd = files.stream()
                    .filter(f -> "SKILL.md".equals(f.relativePath()))
                    .findFirst();
            String name = root.path().isEmpty() ? "SKILL.md" : root.path();
            String description = "";
            boolean parseable = false;
            if (skillMd.isPresent()) {
                try {
                    SkillManifest manifest = parser.parse(
                            new String(skillMd.get().content(), StandardCharsets.UTF_8));
                    name = manifest.name();
                    description = manifest.description();
                    parseable = true;
                } catch (RuntimeException ignored) {
                    // 格式错误的 SKILL.md 被报告为"不可用候选",
                    // 而不是让整个仓库的发现流程失败。
                }
            }
            candidates.add(new DiscoveryCandidate(root.path(), name, description,
                    root.kind().name(), root.declaredBy(), files.size(), parseable));
        }
        String suggested = candidates.stream()
                .filter(DiscoveryCandidate::parseable)
                .findFirst()
                .map(DiscoveryCandidate::path)
                .orElse(candidates.isEmpty() ? null : candidates.get(0).path());
        return new DiscoveryResult(inventory.commitSha(), suggested, candidates);
    }

    public GitSkillImporter.TreeInventory prepareGit(String repoUrl, String ref, java.util.function.BooleanSupplier active) {
        return gitImporter.fetchTree(repoUrl, ref, active);
    }

    @Transactional
    public StagedResult stagePreparedGit(GitSkillImporter.TreeInventory inventory, String subPath) {
        String path = SkillPackageLayout.normalizeSubPath(subPath);
        List<SkillSourceFile> files = SkillPackageLayout.slice(inventory.files(), path);
        var skillMd = files.stream().filter(f -> "SKILL.md".equals(f.relativePath())).findFirst()
                .orElseThrow(() -> new SkillImportException("Prepared package has no SKILL.md"));
        return persistStaged(SkillSourceKind.GIT_HTTPS, gitIdentity(inventory.commitSha(), path),
                new String(skillMd.content(), StandardCharsets.UTF_8), files,
                files.stream().mapToLong(f -> f.content().length).sum());
    }

    private String gitIdentity(String commitSha, String subPath) {
        return subPath.isEmpty() ? "git:" + commitSha : "git:" + commitSha + "#" + subPath;
    }

    /**
     * 仓库市场清单声明的插件源前缀。仅用于归属判定:无法读取的清单直接忽略,
     * 绝不放宽可导入范围,也绝不让发现流程失败。
     */
    private List<String> declaredPluginSources(List<SkillSourceFile> manifests) {
        List<String> declared = new ArrayList<>();
        for (SkillSourceFile manifest : manifests) {
            try {
                Map<String, Object> parsed = json.read(
                        new String(manifest.content(), StandardCharsets.UTF_8),
                        new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, Object>>() {
                        });
                declared.addAll(SkillPackageLayout.declaredPluginSources(parsed));
            } catch (RuntimeException ignored) {
                // 刻意忽略:见方法 javadoc。
            }
        }
        return declared;
    }

    @Transactional
    public StagedResult stageBuiltin(String sourceIdentity, String skillMarkdown,
                                     List<SkillSourceFile> files, long totalBytes) {
        return persistStaged(SkillSourceKind.BUILTIN, "builtin:" + sourceIdentity,
                skillMarkdown, files, totalBytes);
    }

    private StagedResult persistStaged(SkillSourceKind sourceKind,
                                       String sourceIdentity,
                                       String rawSkillMarkdown,
                                       List<SkillSourceFile> files,
                                       long totalBytes) {
        // 现在就校验 manifest(暂存期快速失败),但存储的是原始 markdown;
        // 安装时对同一批字节重新解析,因此"审阅所见"与"实际安装"之间
        // 不可能产生偏差。
        SkillManifest manifest = parser.parse(rawSkillMarkdown);
        List<SkillRepository.StagedFile> stagedFiles = files.stream()
                .map(f -> new SkillRepository.StagedFile(
                        f.relativePath(), Hashes.sha256Hex(f.content()), f.content()))
                .toList();
        String contentHash = stagedContentHashOf(stagedFiles);
        String fileEntries = fileEntriesJson(files);
        SkillStagedImport staged = repository.insertStagedImport(new SkillStagedImport(
                UUID.randomUUID(), sourceKind, sourceIdentity,
                rawSkillMarkdown, fileEntries, totalBytes, files.size(),
                contentHash, SkillStagedImport.Status.STAGED, null,
                Instant.now(), null));
        repository.insertStagedFiles(staged.id(), stagedFiles);
        return new StagedResult(staged.id(), manifest.name(), manifest.description(),
                contentHash, files.size(), totalBytes);
    }

    // ---- 安装 -------------------------------------------------------------

    /**
     * 把一条暂存导入安装为 Skill 行上的不可变版本。若已存在相同源身份的
     * Skill,新版本成为其当前版本;否则创建全新的 Skill 行(默认停用 ——
     * 在显式启用之前,任何东西都不会对 Agent 可见)。
     */
    @Transactional
    public InstalledResult install(UUID stagedImportId) {
        SkillStagedImport staged = repository.findStagedImport(stagedImportId)
                .orElseThrow(() -> new SkillImportException(
                        "Staged import not found: " + stagedImportId));
        if (staged.status() == SkillStagedImport.Status.INSTALLED) {
            throw new SkillImportException("Staged import already installed: "
                    + stagedImportId);
        }
        if (staged.status() == SkillStagedImport.Status.REJECTED) {
            throw new SkillImportException("Staged import was rejected and cannot be installed: "
                    + stagedImportId);
        }

        // 重新解析被审阅过的原始字节;不单独信任行内任何数据
        // (manifest 解析是确定性的,且会做边界校验)。
        SkillManifest manifest = parser.parse(staged.manifest());
        List<SkillRepository.StagedFile> stagedFiles =
                repository.listStagedFiles(stagedImportId);
        List<SkillSourceFile> files = stagedFiles.stream()
                .map(f -> new SkillSourceFile(f.relativePath(), f.content(),
                        detectKind(f.relativePath(), f.content())))
                .toList();
        String contentHash = staged.contentHash();
        if (!contentHash.equals(stagedContentHashOf(stagedFiles))) {
            throw new SkillImportException(
                    "Staged import content hash mismatch — tampered or corrupted staging row");
        }

        // 相同来源类型 + 相同包名 -> 同一 Skill 行(稳定的 skillId,新增不可变
        // 版本)。确实不同的 skill 即使重名也会保留为单独的行,"启用名单唯一"
        // 守卫避免两个同名启用 skill 之间产生语义歧义。
        Skill skill = repository.listSkills().stream()
                .filter(s -> s.sourceKind() == staged.sourceKind()
                        && s.name().equalsIgnoreCase(manifest.name()))
                .findFirst()
                .orElseGet(() -> repository.insertSkill(new Skill(
                        UUID.randomUUID(),
                        "sk_" + contentHash.substring(0, 12),
                        manifest.name(),
                        manifest.description(),
                        staged.sourceKind(),
                        staged.sourceIdentity(),
                        null,
                        false,
                        Instant.now(),
                        Instant.now())));

        int nextVersion = repository.nextVersionNo(skill.id());
        // 不可变的内容身份:重复安装字节完全相同的内容(同包、同可选资源集)
        // 直接复用已有的不可变版本,而不是失败或产生重复。
        Optional<SkillVersion> existing = repository.findVersionByContentHash(contentHash);
        SkillVersion version;
        if (existing.isPresent()) {
            version = existing.get();
        } else {
            version = repository.insertVersion(new SkillVersion(
                    UUID.randomUUID(), skill.id(), nextVersion, contentHash,
                    manifestJson(manifest), manifest.instructions(),
                    staged.sourceIdentity(), files.size(), staged.totalBytes(), Instant.now()));
            List<SkillPackageFile> packageFiles = files.stream()
                    .map(f -> new SkillPackageFile(UUID.randomUUID(), version.id(),
                            f.relativePath(), f.kind(), f.content().length,
                            Hashes.sha256Hex(f.content()), f.content()))
                    .toList();
            repository.insertPackageFiles(packageFiles);
        }
        repository.updateSkillCurrentVersion(skill.id(), version.id(), manifest.description());
        repository.updateStagedImportStatus(stagedImportId,
                SkillStagedImport.Status.INSTALLED, null);
        // 安装成功后暂存文件字节不再需要。
        repository.deleteStagedFiles(stagedImportId);
        // 尽力而为的本地镜像(数据库仍是权威);若某个已知版本的文件在磁盘上
        // 丢失,这里也会顺带重新投影。
        localMirror.mirrorVersion(skill.skillId(), version.versionNo(), files);
        return new InstalledResult(skill.skillId(), skill.id(), version.id(),
                existing.isPresent() ? nextVersion - 1 : nextVersion);
    }

    // ---- 生命周期 -----------------------------------------------------------

    @Transactional
    public void enable(UUID skillRowId) {
        Skill skill = repository.findSkillById(skillRowId)
                .orElseThrow(() -> new SkillImportException("Skill not found: " + skillRowId));
        if (skill.currentVersionId() == null) {
            throw new SkillImportException("Skill has no installed version to enable: "
                    + skill.skillId());
        }
        // 语义歧义守卫:同一个显示名最多只能属于一个已启用的 Skill。
        repository.listSkills().stream()
                .filter(other -> other.enabled() && other.name().equals(skill.name())
                        && !other.id().equals(skill.id()))
                .findFirst()
                .ifPresent(conflict -> {
                    throw new SkillImportException(
                            "Another enabled Skill already uses the name '"
                                    + skill.name() + "' (disable it first): "
                                    + conflict.skillId());
                });
        repository.updateSkillEnabled(skillRowId, true);
    }

    @Transactional
    public void disable(UUID skillRowId) {
        repository.updateSkillEnabled(skillRowId, false);
    }

    @Transactional
    public void delete(UUID skillRowId) {
        Skill skill = repository.findSkillById(skillRowId)
                .orElseThrow(() -> new SkillImportException("Skill not found: " + skillRowId));
        repository.deleteVersionsAndFiles(skillRowId);
        repository.deleteSkill(skillRowId);
        localMirror.removeSkill(skill.skillId());
    }

    @Transactional
    public void rejectStaged(UUID stagedImportId, String reason) {
        repository.updateStagedImportStatus(stagedImportId,
                SkillStagedImport.Status.REJECTED, reason);
        repository.deleteStagedFiles(stagedImportId);
    }

    @Transactional
    public void deleteStaged(UUID stagedImportId) {
        repository.deleteStagedImport(stagedImportId);
    }

    // ---- 辅助方法 -----------------------------------------------------------

    private String contentHashOf(List<SkillSourceFile> files) {
        List<String> parts = files.stream()
                .map(f -> f.relativePath() + ":" + Hashes.sha256Hex(f.content()))
                .sorted()
                .toList();
        return Hashes.sha256Hex(String.join("\n", parts));
    }

    private String stagedContentHashOf(List<SkillRepository.StagedFile> files) {
        List<String> parts = files.stream()
                .map(f -> f.relativePath() + ":" + f.sha256())
                .sorted()
                .toList();
        return Hashes.sha256Hex(String.join("\n", parts));
    }

    private String manifestJson(SkillManifest manifest) {
        return json.write(manifest);
    }

    private String fileEntriesJson(List<SkillSourceFile> files) {
        List<Map<String, Object>> entries = files.stream()
                .map(f -> Map.<String, Object>of(
                        "path", f.relativePath(),
                        "kind", f.kind().code(),
                        "size", f.content().length,
                        "sha256", Hashes.sha256Hex(f.content())))
                .toList();
        return json.write(entries);
    }

    private SkillPackageFile.FileKind detectKind(String path, byte[] content) {
        if ("SKILL.md".equals(path)) {
            return SkillPackageFile.FileKind.SKILL_MD;
        }
        if (content == null || content.length == 0) {
            return SkillPackageFile.FileKind.BINARY;
        }
        int textScore = 0;
        int total = Math.min(content.length, 4096);
        for (int i = 0; i < total; i++) {
            int b = content[i] & 0xFF;
            if (b == 0) {
                return SkillPackageFile.FileKind.BINARY;
            }
            if (b == 9 || b == 10 || b == 13 || (b >= 32 && b <= 126) || b >= 0x80) {
                textScore++;
            }
        }
        return ((double) textScore / total) >= 0.9
                ? SkillPackageFile.FileKind.TEXT : SkillPackageFile.FileKind.BINARY;
    }

    public record StagedResult(UUID stagedImportId, String name, String description,
                               String contentHash, int fileCount, long totalBytes) {
    }

    /**
     * 仓库中发现的一个 Skill 包。
     *
     * @param path      相对仓库的目录("" = 仓库根)
     * @param parseable 其 SKILL.md 能否被解析为 manifest
     */
    public record DiscoveryCandidate(String path, String name, String description,
                                     String kind, String declaredBy, int fileCount,
                                     boolean parseable) {
    }

    /**
     * 供选择 Skill 包使用的仓库清单。
     *
     * @param suggestedPath 首个可用候选的路径;仓库完全不含 Skill 包时为 null
     */
    public record DiscoveryResult(String commitSha, String suggestedPath,
                                  List<DiscoveryCandidate> candidates) {
    }

    public record InstalledResult(String skillId, UUID skillRowId, UUID versionId,
                                  int versionNo) {
    }
}
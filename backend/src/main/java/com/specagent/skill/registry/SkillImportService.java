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
 * Skill import lifecycle: stage (validate + hash without executing), review,
 * install (immutable version), enable/disable/delete.
 *
 * <p>Nothing is executed during staging or install: no scripts, no dependency
 * installation, no hooks. The database is the authoritative state; installed
 * versions are immutable rows keyed by content hash.
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

    // ---- staging ---------------------------------------------------------

    /**
     * Stages an uploaded ZIP for review. Validates structure + bounds + SKILL.md
     * metadata and stores the validated content under a staged import id.
     * No execution of any kind happens here.
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
     * Stages an HTTPS git import for review. The resolved commit becomes the
     * immutable source identity; {@code subPath} selects one Skill directory
     * inside a larger repository (blank = the repository root package).
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

    /** Stages the repository-root Skill package (no subdirectory selected). */
    @Transactional
    public StagedResult stageGit(String repoUrl, String ref) {
        return stageGit(repoUrl, ref, null);
    }

    /**
     * Lists the Skill packages a repository offers, without staging anything.
     * A repository holding one Skill returns exactly one candidate; a library
     * or marketplace returns its Skill directories so one can be chosen.
     */
    public DiscoveryResult discoverGit(String repoUrl, String ref) {
        GitSkillImporter.TreeInventory inventory = gitImporter.fetchTree(repoUrl, ref);
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
                    // A malformed SKILL.md is reported as an unusable candidate
                    // instead of failing discovery for the whole repository.
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

    private String gitIdentity(String commitSha, String subPath) {
        return subPath.isEmpty() ? "git:" + commitSha : "git:" + commitSha + "#" + subPath;
    }

    /**
     * Plugin source prefixes declared by the repository's marketplace
     * manifests. Attribution only: an unreadable manifest is ignored, it never
     * widens what may be imported and never fails the discovery.
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
                // Ignored by design: see javadoc.
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
        // Validate the manifest now (fail-fast at staging time) but store the
        // raw markdown; install re-parses the same bytes so no drift is
        // possible between what was reviewed and what gets installed.
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

    // ---- install ---------------------------------------------------------

    /**
     * Installs a staged import as an immutable version on a Skill row. When a
     * Skill with the same source identity exists, the new version becomes its
     * current version; otherwise a fresh Skill row is created (disabled by
     * default — nothing becomes agent-visible until explicitly enabled).
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

        // Re-parse the exact reviewed bytes; nothing is trusted from the row
        // alone (manifest parse is deterministic and validates bounds).
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

        // Same source kind + package name -> same Skill row (stable skillId,
        // new immutable version). A genuinely different skill sharing the
        // name is kept as a separate row and the enabled-name unique guard
        // prevents semantic ambiguity between two enabled same-name skills.
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
        // Immutable content identity: installing byte-identical content again
        // (same package, same optional resource set) reuses the existing
        // immutable version instead of failing or duplicating it.
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
        // Staged file bytes are no longer needed after a successful install.
        repository.deleteStagedFiles(stagedImportId);
        // Best-effort local mirror (DB stays authoritative); also re-projects
        // an already-known version whose files were lost on disk.
        localMirror.mirrorVersion(skill.skillId(), version.versionNo(), files);
        return new InstalledResult(skill.skillId(), skill.id(), version.id(),
                existing.isPresent() ? nextVersion - 1 : nextVersion);
    }

    // ---- lifecycle -------------------------------------------------------

    @Transactional
    public void enable(UUID skillRowId) {
        Skill skill = repository.findSkillById(skillRowId)
                .orElseThrow(() -> new SkillImportException("Skill not found: " + skillRowId));
        if (skill.currentVersionId() == null) {
            throw new SkillImportException("Skill has no installed version to enable: "
                    + skill.skillId());
        }
        // Semantic ambiguity guard: only one enabled Skill may own a display name.
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

    // ---- helpers ---------------------------------------------------------

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
     * One Skill package found inside a repository.
     *
     * @param path      repository-relative directory ("" = repository root)
     * @param parseable whether its SKILL.md could be parsed as a manifest
     */
    public record DiscoveryCandidate(String path, String name, String description,
                                     String kind, String declaredBy, int fileCount,
                                     boolean parseable) {
    }

    /**
     * Repository inventory for choosing a Skill package.
     *
     * @param suggestedPath path of the first usable candidate, or null when the
     *                      repository holds no Skill package at all
     */
    public record DiscoveryResult(String commitSha, String suggestedPath,
                                  List<DiscoveryCandidate> candidates) {
    }

    public record InstalledResult(String skillId, UUID skillRowId, UUID versionId,
                                  int versionNo) {
    }
}
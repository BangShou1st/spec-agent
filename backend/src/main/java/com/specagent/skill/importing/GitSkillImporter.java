package com.specagent.skill.importing;

import com.specagent.common.network.OutboundNetworkPolicy;
import com.specagent.common.network.OutboundPolicyViolationException;
import com.specagent.skill.config.SkillProperties;
import com.specagent.skill.domain.SkillPackageFile;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * HTTPS Git Skill import backed by JGit. The client never runs hooks, never
 * initializes submodules, and never smudges LFS content — matching the
 * no-execution/no-dependency-installation import posture.
 *
 * <p>Security controls (fail-closed):
 * <ul>
 *   <li>HTTPS only (via {@link OutboundNetworkPolicy});</li>
 *   <li>URL validated before connect — private/link-local/metadata hosts are
 *       rejected by resolved address, not just by name;</li>
 *   <li>depth-1 bare clone bounds the download to the resolved commit;</li>
 *   <li>extracted tree walked with path containment + size limits;</li>
 *   <li>the resolved commit SHA becomes the immutable source identity — ref
 *       moves never rewrite an installed version.</li>
 * </ul>
 */
@Component
public class GitSkillImporter {

    private static final String SKILL_MD = "SKILL.md";

    private final SkillProperties properties;
    private final OutboundNetworkPolicy policy;

    public GitSkillImporter(SkillProperties properties, OutboundNetworkPolicy policy) {
        this.properties = properties;
        this.policy = policy;
    }

    /**
     * Imports a Skill from an HTTPS git repository into an in-memory validated
     * package model. The working tree of the resolved commit is walked with
     * the same containment and size rules as ZIP imports.
     *
     * @param ref commit-ish (branch/tag/SHA) to pin; blank means remote HEAD
     */
    public ExtractedResult importHttps(String repoUrl, String ref) {
        String url = policy.validateOutboundUrl(repoUrl, properties.getGitMaxRedirects())
                .toString();
        if (!url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new SkillImportException("Git Skill import requires an HTTPS URL");
        }
        String pinnedRef = sanitizeRef(ref);

        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("spec-agent-git-skill-");
        } catch (IOException ex) {
            throw new SkillImportException("Failed to create git staging directory", ex);
        }

        try {
            String commitSha = cloneBare(url, pinnedRef, tempDir);
            return extractTree(tempDir, commitSha);
        } catch (GitAPIException | IOException ex) {
            throw new SkillImportException("Git import failed: "
                    + ex.getClass().getSimpleName(), ex);
        } finally {
            deleteRecursively(tempDir.toFile());
        }
    }

    /**
     * Bare depth-1 clone: downloads only the pinned commit's objects, creates
     * no working tree, and — because JGit is the client — never runs any git
     * hook, never initializes submodules, and never smudges LFS content.
     */
    private String cloneBare(String url, String ref, Path dir)
            throws GitAPIException, IOException {
        try (Git ignored = Git.cloneRepository()
                .setURI(url)
                .setDirectory(dir.toFile())
                .setBare(true)
                .setDepth(1)
                .setNoCheckout(true)
                .setBranch(ref == null ? Constants.HEAD : ref)
                .setTimeout(properties.getGitTimeoutSeconds())
                .setCloneAllBranches(false)
                .setNoTags()
                .call()) {
            Repository repository = ignored.getRepository();
            var resolved = repository.resolve(Constants.HEAD);
            if (resolved == null) {
                throw new SkillImportException("Git repository resolved no HEAD commit");
            }
            return resolved.name();
        }
    }

    private String sanitizeRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String stripped = ref.strip();
        if (stripped.contains("..") || stripped.contains("://")
                || stripped.startsWith("-") || stripped.contains(" ")
                || stripped.length() > 200) {
            throw new SkillImportException("Invalid git ref: " + stripped);
        }
        return stripped;
    }

    private ExtractedResult extractTree(Path bareDir, String commitSha) throws IOException {
        Path gitDir = bareDir.resolve(".git");
        if (!Files.isDirectory(gitDir)) {
            gitDir = bareDir;
        }
        Repository repository = new org.eclipse.jgit.storage.file.FileRepositoryBuilder()
                .setGitDir(gitDir.toFile())
                .build();
        try (RevWalk revWalk = new RevWalk(repository)) {
            var commitId = repository.resolve(commitSha);
            if (commitId == null) {
                throw new SkillImportException("Resolved commit not present after clone");
            }
            RevCommit commit = revWalk.parseCommit(commitId);
            RevTree tree = commit.getTree();
            List<SkillSourceFile> files = new ArrayList<>();
            long totalBytes = 0;
            int fileCount = 0;

            try (TreeWalk treeWalk = new TreeWalk(repository)) {
                treeWalk.addTree(tree);
                treeWalk.setRecursive(true);
                while (treeWalk.next()) {
                    String path = treeWalk.getPathString();
                    if (path.startsWith(".git") || path.startsWith(".github/")) {
                        continue;
                    }
                    if (fileCount >= properties.getMaxFiles()) {
                        throw new SkillImportException("Git package contains more than "
                                + properties.getMaxFiles() + " files");
                    }
                    ObjectLoader loader = repository.open(treeWalk.getObjectId(0));
                    byte[] content = loader.getBytes();
                    totalBytes += content.length;
                    if (totalBytes > properties.getMaxExtractedBytes()) {
                        throw new SkillImportException("Git package exceeds the "
                                + properties.getMaxExtractedBytes() + " byte limit");
                    }
                    String normalized = normalizePath(path);
                    SkillPackageFile.FileKind kind = kindFor(normalized, content);
                    files.add(new SkillSourceFile(normalized, content, kind));
                    fileCount++;
                }
            }

            SkillSourceFile skillMd = files.stream()
                    .filter(f -> SKILL_MD.equals(f.relativePath()))
                    .findFirst()
                    .orElseThrow(() -> new SkillImportException(
                            "Git Skill package is missing SKILL.md at the root"));

            files.sort(Comparator.comparing(SkillSourceFile::relativePath));
            return new ExtractedResult(commitSha, skillMd.content(), files, totalBytes);
        } finally {
            repository.close();
        }
    }

    private String normalizePath(String path) {
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..")
                || normalized.startsWith(".")) {
            throw new SkillImportException("Git package entry escapes the package root: "
                    + path);
        }
        return normalized;
    }

    private SkillPackageFile.FileKind kindFor(String path, byte[] content) {
        if (SKILL_MD.equals(path)) {
            return SkillPackageFile.FileKind.SKILL_MD;
        }
        if (content.length == 0) {
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

    private void deleteRecursively(java.io.File file) {
        if (file == null || !file.exists()) {
            return;
        }
        java.io.File[] children = file.listFiles();
        if (children != null) {
            for (java.io.File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    public record ExtractedResult(String commitSha, byte[] skillMarkdown,
                                  List<SkillSourceFile> files, long totalBytes) {
    }
}
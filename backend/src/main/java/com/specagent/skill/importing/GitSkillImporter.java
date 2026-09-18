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
import java.util.Optional;
import java.util.stream.Collectors;

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

    /** Upper bound on Skill directories named in one failure message. */
    private static final int MAX_REPORTED_SKILL_ROOTS = 5;

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
        return importHttps(repoUrl, ref, null);
    }

    /**
     * Imports one Skill package out of the repository. {@code subPath} selects
     * a nested Skill directory (for example {@code skills/brainstorming});
     * blank selects the repository root package.
     */
    public ExtractedResult importHttps(String repoUrl, String ref, String subPath) {
        TreeInventory inventory = fetchTree(repoUrl, ref);
        List<SkillSourceFile> files = SkillPackageLayout.slice(inventory.files(), subPath);
        long totalBytes = files.stream().mapToLong(f -> f.content().length).sum();
        SkillSourceFile skillMd = requireRootSkillMarkdown(files, inventory.files(), subPath);
        return new ExtractedResult(inventory.commitSha(), skillMd.content(), files, totalBytes);
    }

    /**
     * Walks the resolved commit's tree without requiring a package shape, so a
     * repository that holds many Skills can be inspected and offered for
     * selection instead of failing outright. Rules are identical to extraction:
     * hidden entries skipped, path containment, file-count and byte bounds.
     */
    public TreeInventory fetchTree(String repoUrl, String ref) {
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
            GitTransportProxy.Route route = GitTransportProxy.resolve(properties.getGitProxy());
            String commitSha;
            try {
                commitSha = GitTransportProxy.callWith(route,
                        () -> cloneBare(url, pinnedRef, tempDir));
            } catch (Exception ex) {
                throw transportFailure(url, route, ex);
            }
            return extractTree(tempDir, commitSha);
        } catch (IOException ex) {
            throw new SkillImportException("Git import failed: "
                    + ex.getClass().getSimpleName(), ex);
        } finally {
            deleteRecursively(tempDir.toFile());
        }
    }

    /**
     * A transport failure is reported with its real cause and with the route
     * that was used, so "TransportException" is never a dead end: the operator
     * can see whether the JVM went direct while the browser used a proxy, and
     * which knob changes it.
     */
    private SkillImportException transportFailure(String url, GitTransportProxy.Route route,
                                                  Exception cause) {
        StringBuilder message = new StringBuilder("Git import failed: ")
                .append(cause.getClass().getSimpleName())
                .append(" via ").append(route.description());
        String causeMessage = rootMessage(cause);
        if (causeMessage != null && !causeMessage.isBlank()) {
            message.append(" (").append(bound(causeMessage)).append(')');
        }
        message.append(". If this host needs a proxy, set ")
                .append(GitTransportProxy.PROPERTY_NAME).append("=host:port (or ")
                .append(GitTransportProxy.MODE_DIRECT).append(" to force a direct connection)");
        return new SkillImportException(message.toString(), cause);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        String message = null;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return message;
    }

    private static String bound(String text) {
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }

    /**
     * Walked, validated contents of one resolved commit.
     *
     * @param files     package files (hidden entries excluded)
     * @param manifests marketplace manifests, read for attribution only
     */
    public record TreeInventory(String commitSha, List<SkillSourceFile> files, long totalBytes,
                                List<SkillSourceFile> manifests) {
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

    private TreeInventory extractTree(Path bareDir, String commitSha) throws IOException {
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
            List<SkillSourceFile> manifests = new ArrayList<>();
            long totalBytes = 0;
            int fileCount = 0;

            try (TreeWalk treeWalk = new TreeWalk(repository)) {
                treeWalk.addTree(tree);
                treeWalk.setRecursive(true);
                while (treeWalk.next()) {
                    String path = treeWalk.getPathString();
                    // Marketplace manifests are read for attribution only: they
                    // stay out of the package (they are repository metadata),
                    // but discovery needs them to know which plugin owns which
                    // Skill directory.
                    EntryDisposition disposition = dispositionOf(path);
                    if (disposition == EntryDisposition.SKIP) {
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
                    if (disposition == EntryDisposition.MANIFEST) {
                        manifests.add(new SkillSourceFile(path, content,
                                SkillPackageFile.FileKind.TEXT));
                        continue;
                    }
                    String normalized = normalizePath(path);
                    SkillPackageFile.FileKind kind = kindFor(normalized, content);
                    files.add(new SkillSourceFile(normalized, content, kind));
                    fileCount++;
                }
            }

            files.sort(Comparator.comparing(SkillSourceFile::relativePath));
            return new TreeInventory(commitSha, List.copyOf(files), totalBytes,
                    List.copyOf(manifests));
        } finally {
            repository.close();
        }
    }

    /** What one repository tree entry becomes during an import. */
    enum EntryDisposition {
        /** Becomes part of the Skill package. */
        PACKAGE,
        /** Read for attribution, never packaged. */
        MANIFEST,
        /** Repository metadata: ignored entirely. */
        SKIP
    }

    /**
     * Classifies one tree entry. A marketplace manifest is metadata too, but it
     * must be readable even though it is never packaged — discovery uses it to
     * attribute Skill directories to a plugin.
     */
    static EntryDisposition dispositionOf(String path) {
        if (SkillPackageLayout.MARKETPLACE_MANIFESTS.contains(path)) {
            return EntryDisposition.MANIFEST;
        }
        return isHiddenEntry(path) ? EntryDisposition.SKIP : EntryDisposition.PACKAGE;
    }

    /**
     * Repository metadata is never Skill content. Hidden entries — {@code .git},
     * {@code .github/...}, {@code .agents/...}, {@code .claude-plugin/...} and
     * every other dot-prefixed segment — are skipped exactly like the VCS
     * bookkeeping always was, so a marketplace-style monorepo carrying dot
     * directories next to its skills no longer fails the whole import.
     */
    static boolean isHiddenEntry(String path) {
        if (path.startsWith(".")) {
            return true;
        }
        for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
            if (slash + 1 < path.length() && path.charAt(slash + 1) == '.') {
                return true;
            }
        }
        return false;
    }

    /**
     * A Skill package is one Skill: SKILL.md at the package root. When the
     * selected directory is instead part of a library (a plugin marketplace or
     * a monorepo), the failure names the Skill directories the repository
     * actually offers, so the caller can retry with one of them instead of
     * guessing.
     *
     * @param files     the selected package files (already sliced)
     * @param catalogue every file in the repository, used only to report candidates
     * @param subPath   the selected subdirectory, "" for the repository root
     */
    SkillSourceFile requireRootSkillMarkdown(List<SkillSourceFile> files,
                                             List<SkillSourceFile> catalogue,
                                             String subPath) {
        Optional<SkillSourceFile> atRoot = files.stream()
                .filter(f -> SKILL_MD.equals(f.relativePath()))
                .findFirst();
        if (atRoot.isPresent()) {
            return atRoot.get();
        }
        String selected = subPath == null || subPath.isBlank() ? "" : SkillPackageLayout
                .normalizeSubPath(subPath);
        List<String> skillRoots = SkillPackageLayout.discover(
                        catalogue.stream().map(SkillSourceFile::relativePath).toList(), List.of())
                .stream()
                .map(SkillPackageLayout.SkillRoot::path)
                .filter(path -> !path.isEmpty())
                .toList();
        if (skillRoots.isEmpty()) {
            throw new SkillImportException("Git Skill package is missing SKILL.md at the root");
        }
        String sample = skillRoots.stream().limit(MAX_REPORTED_SKILL_ROOTS)
                .collect(Collectors.joining(", "));
        String tail = (skillRoots.size() > MAX_REPORTED_SKILL_ROOTS ? ", …" : "");
        if (selected.isEmpty()) {
            throw new SkillImportException("Git repository is not a single Skill package:"
                    + " no SKILL.md at the repository root, but " + skillRoots.size()
                    + " Skill directories were found (" + sample + tail
                    + "). Import one of them, for example " + skillRoots.get(0) + ".");
        }
        throw new SkillImportException("Selected directory is not a Skill package"
                + " (no SKILL.md in " + selected + "). The repository offers "
                + skillRoots.size() + " Skill directories (" + sample + tail
                + "), for example " + skillRoots.get(0) + ".");
    }

    /** Overload for the repository-root case. */
    SkillSourceFile requireRootSkillMarkdown(List<SkillSourceFile> files) {
        return requireRootSkillMarkdown(files, files, "");
    }

    /** Package-private for path-rule tests; the containment rule itself is unchanged. */
    String normalizePath(String path) {
        String normalized = path.replace('\\', '/');
        // Hidden entries were already skipped, so every remaining reachable
        // case here is a genuine escape attempt.
        if (normalized.startsWith("/") || normalized.contains("..")) {
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
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
 * 文件名:GitSkillImporter.java
 *
 * 用途:基于 JGit 的 HTTPS Git Skill 导入器。客户端绝不运行 hooks、绝不
 * 初始化子模块、绝不 smudge LFS 内容 —— 与"不执行代码/不安装依赖"的导入
 * 安全姿态保持一致。
 *
 * 安全控制(默认失败关闭):
 * - 仅允许 HTTPS(经由 {@link OutboundNetworkPolicy});
 * - 连接前校验 URL —— 私有/链路本地/元数据主机按解析后的地址拒绝,
 *       而不只是按主机名拒绝;
 * - depth-1 裸 clone 把下载范围限制在解析出的那个 commit;
 * - 提取的目录树以路径包含性 + 大小限额遍历;
 * - 解析出的 commit SHA 成为不可变的源身份 —— ref 移动绝不会改写已安装
 *       的版本。 */
@Component
public class GitSkillImporter {

    private static final String SKILL_MD = "SKILL.md";

    /** 单条失败消息中最多列出的 Skill 目录数量上限。 */
    private static final int MAX_REPORTED_SKILL_ROOTS = 5;

    private final SkillProperties properties;
    private final OutboundNetworkPolicy policy;

    public GitSkillImporter(SkillProperties properties, OutboundNetworkPolicy policy) {
        this.properties = properties;
        this.policy = policy;
    }

    /**
     * 从 HTTPS git 仓库导入一个 Skill,产出内存中的、已校验的包模型。解析出的
     * commit 工作树按与 ZIP 导入相同的包含性与大小规则遍历。
     *
     * @param ref 要固定的 commit-ish(分支/标签/SHA);留空表示远程 HEAD
     */
    public ExtractedResult importHttps(String repoUrl, String ref) {
        return importHttps(repoUrl, ref, null);
    }

    /**
     * 从仓库导入一个 Skill 包。{@code subPath} 用于选择嵌套的 Skill 目录
     * (例如 {@code skills/brainstorming});留空表示仓库根目录的包。
     */
    public ExtractedResult importHttps(String repoUrl, String ref, String subPath) {
        TreeInventory inventory = fetchTree(repoUrl, ref);
        List<SkillSourceFile> files = SkillPackageLayout.slice(inventory.files(), subPath);
        long totalBytes = files.stream().mapToLong(f -> f.content().length).sum();
        SkillSourceFile skillMd = requireRootSkillMarkdown(files, inventory.files(), subPath);
        return new ExtractedResult(inventory.commitSha(), skillMd.content(), files, totalBytes);
    }

    /**
     * 遍历解析出的 commit 树但不要求包形态,从而可以检视一个含多个 Skill 的
     * 仓库并供用户选择,而不是直接失败。规则与提取完全一致:跳过隐藏条目、
     * 路径包含性、文件数与字节上限。
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
     * 传输失败会带上真实原因和当时使用的路由一起上报,让 "TransportException"
     * 不再是死胡同:运维可以看清 JVM 是直连而浏览器走了代理,以及改哪个
     * 配置项能解决。
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
     * 一次解析出的 commit 的、已遍历校验的内容。
     *
     * @param files     包文件(已排除隐藏条目)
     * @param manifests 市场清单文件,仅用于归属判定而读取
     */
    public record TreeInventory(String commitSha, List<SkillSourceFile> files, long totalBytes,
                                List<SkillSourceFile> manifests) {
    }

    /**
     * 裸 depth-1 clone:只下载被固定 commit 的对象,不创建工作树;而且因为
     * 客户端是 JGit,绝不会运行任何 git hook、不会初始化子模块、不会
     * smudge LFS 内容。
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
                    // 市场清单只为归属判定而读取:它们不进入包(属于仓库
                    // 元数据),但发现功能需要靠它知道哪个 Skill 目录属于
                    // 哪个插件。
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

    /** 一次导入中,单个仓库树条目的去向。 */
    enum EntryDisposition {
        /** 成为 Skill 包的一部分。 */
        PACKAGE,
        /** 仅供归属判定读取,绝不打包。 */
        MANIFEST,
        /** 仓库元数据:完全忽略。 */
        SKIP
    }

    /**
     * 判定单个树条目的去向。市场清单文件同样属于元数据,虽然绝不打包,但必须
     * 可读 —— 发现功能靠它把 Skill 目录归属到插件。
     */
    static EntryDisposition dispositionOf(String path) {
        if (SkillPackageLayout.MARKETPLACE_MANIFESTS.contains(path)) {
            return EntryDisposition.MANIFEST;
        }
        return isHiddenEntry(path) ? EntryDisposition.SKIP : EntryDisposition.PACKAGE;
    }

    /**
     * 仓库元数据绝不是 Skill 内容。隐藏条目 —— {@code .git}、
     * {@code .github/...}、{@code .agents/...}、{@code .claude-plugin/...} 以及
     * 其他一切以点开头的路径段 —— 一律像 VCS 簿记文件那样跳过,这样市场式
     * monorepo 在 skill 旁边携带点目录时,不会再导致整个导入失败。
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
     * 一个 Skill 包就是一个 Skill:SKILL.md 必须位于包根。若所选目录实际是
     * 一个"库"(插件市场或 monorepo)的一部分,失败消息会列出仓库实际提供
     * 的 Skill 目录,调用方可改用其中之一重试,而不用猜。
     *
     * @param files     所选包文件(已切分)
     * @param catalogue 仓库内全部文件,仅用于报告候选目录
     * @param subPath   所选子目录,仓库根为 ""
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

    /** 仓库根目录场景的重载。 */
    SkillSourceFile requireRootSkillMarkdown(List<SkillSourceFile> files) {
        return requireRootSkillMarkdown(files, files, "");
    }

    /** 包级可见以便路径规则单测;包含性规则本身不变。 */
    String normalizePath(String path) {
        String normalized = path.replace('\\', '/');
        // 隐藏条目已被跳过,因此走到这里的可触发情形都是真正的越界尝试。
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
package com.specagent.skill.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Skill Runtime limits and security bounds. Every limit is a semantic
 * configuration knob with conservative defaults; model output can never
 * change them.
 */
@Component
@ConfigurationProperties(prefix = "spec.agent.skill")
public class SkillProperties {

    // ---- import bounds -------------------------------------------------
    private long maxArchiveBytes = 2_097_152;        // 2 MiB
    private long maxExtractedBytes = 10_485_760;     // 10 MiB
    private int maxFiles = 256;
    private int maxDepth = 8;
    private int maxPathChars = 512;

    // ---- catalog bounds ------------------------------------------------
    private int maxVisible = 24;
    private int maxMetadataBytes = 4096;
    private int maxDescriptionChars = 320;

    // ---- search / activation / resource bounds -------------------------
    private int searchMaxResults = 10;
    private int activationMaxInstructionBytes = 60_000;
    private int resourceMaxInlineBytes = 20_000;
    private int maxResourcesListed = 500;

    // ---- git import bounds ---------------------------------------------
    private long gitCloneBytes = 10_485_760;         // 10 MiB
    private int gitTimeoutSeconds = 30;
    private int gitMaxRedirects = 3;
    /**
     * Outbound route for the Skill git import: AUTO (default — proxy env var,
     * else a listening local proxy, else direct), DIRECT, or host:port. The JVM
     * does not inherit the OS/browser proxy on its own, which is why a clone can
     * fail even when the browser reaches the same host.
     */
    /** Default outbound git route value; resolution semantics live in importing.GitTransportProxy. */
    public static final String GIT_PROXY_MODE_AUTO = "AUTO";

    private String gitProxy = GIT_PROXY_MODE_AUTO;

    // ---- local mirror ---------------------------------------------------
    /**
     * DB-authoritative local mirror of installed packages. The database stays
     * the only activation-time authority; the mirror lets users browse, back
     * up and version their skills on disk. Null root resolves to
     * {@code ./data/skills} (relative to the backend working directory) at
     * use time so tests can stay hermetic by disabling the mirror or pointing
     * it at a temp dir.
     */
    private boolean localMirrorEnabled = true;
    private String localMirrorRoot;

    // ---- public accessors ----------------------------------------------
    public long getMaxArchiveBytes() { return maxArchiveBytes; }
    public void setMaxArchiveBytes(long v) { maxArchiveBytes = v; }

    public long getMaxExtractedBytes() { return maxExtractedBytes; }
    public void setMaxExtractedBytes(long v) { maxExtractedBytes = v; }

    public int getMaxFiles() { return maxFiles; }
    public void setMaxFiles(int v) { maxFiles = v; }

    public int getMaxDepth() { return maxDepth; }
    public void setMaxDepth(int v) { maxDepth = v; }

    public int getMaxPathChars() { return maxPathChars; }
    public void setMaxPathChars(int v) { maxPathChars = v; }

    public int getMaxVisible() { return maxVisible; }
    public void setMaxVisible(int v) { maxVisible = v; }

    public int getMaxMetadataBytes() { return maxMetadataBytes; }
    public void setMaxMetadataBytes(int v) { maxMetadataBytes = v; }

    public int getMaxDescriptionChars() { return maxDescriptionChars; }
    public void setMaxDescriptionChars(int v) { maxDescriptionChars = v; }

    public int getSearchMaxResults() { return searchMaxResults; }
    public void setSearchMaxResults(int v) { searchMaxResults = v; }

    public int getActivationMaxInstructionBytes() { return activationMaxInstructionBytes; }
    public void setActivationMaxInstructionBytes(int v) { activationMaxInstructionBytes = v; }

    public int getResourceMaxInlineBytes() { return resourceMaxInlineBytes; }
    public void setResourceMaxInlineBytes(int v) { resourceMaxInlineBytes = v; }

    public int getMaxResourcesListed() { return maxResourcesListed; }
    public void setMaxResourcesListed(int v) { maxResourcesListed = v; }

    public long getGitCloneBytes() { return gitCloneBytes; }
    public void setGitCloneBytes(long v) { gitCloneBytes = v; }

    public int getGitTimeoutSeconds() { return gitTimeoutSeconds; }
    public void setGitTimeoutSeconds(int v) { gitTimeoutSeconds = v; }

    public int getGitMaxRedirects() { return gitMaxRedirects; }
    public void setGitMaxRedirects(int v) { gitMaxRedirects = v; }

    public String getGitProxy() { return gitProxy; }
    public void setGitProxy(String v) { gitProxy = v; }

    public boolean isLocalMirrorEnabled() { return localMirrorEnabled; }
    public void setLocalMirrorEnabled(boolean v) { localMirrorEnabled = v; }

    public String getLocalMirrorRoot() { return localMirrorRoot; }
    public void setLocalMirrorRoot(String v) { localMirrorRoot = v; }

    public static SkillProperties defaults() {
        return new SkillProperties();
    }
}
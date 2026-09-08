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

    public static SkillProperties defaults() {
        return new SkillProperties();
    }
}
package com.specagent.skill.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件名:SkillProperties.java
 *
 * 用途:Skill Runtime 的限额与安全边界配置。每一项都是语义化的配置旋钮,
 * 取保守默认值;模型输出永远无法修改这些上限。
 */
@Component
@ConfigurationProperties(prefix = "spec.agent.skill")
public class SkillProperties {

    // ---- 导入限额 -------------------------------------------------------
    private long maxArchiveBytes = 2_097_152;        // 2 MiB
    private long maxExtractedBytes = 10_485_760;     // 10 MiB
    private int maxFiles = 256;
    private int maxDepth = 8;
    private int maxPathChars = 512;

    // ---- 目录限额 --------------------------------------------------------
    private int maxVisible = 24;
    private int maxMetadataBytes = 4096;
    private int maxDescriptionChars = 320;

    // ---- 搜索 / 激活 / 资源限额 --------------------------------------------
    private int searchMaxResults = 10;
    private int activationMaxInstructionBytes = 60_000;
    private int resourceMaxInlineBytes = 20_000;
    private int maxResourcesListed = 500;

    // ---- git 导入限额 ----------------------------------------------------
    private long gitCloneBytes = 10_485_760;         // 10 MiB
    private int gitTimeoutSeconds = 30;
    private int gitMaxRedirects = 3;
    /**
     * Skill git 导入的出站路由:AUTO(默认 —— 优先代理环境变量,其次探测本机
     * 正在监听的代理端口,否则直连)、DIRECT 或 host:port。JVM 不会自动继承
     * 操作系统/浏览器的代理设置,这正是浏览器能访问同一主机而 clone 却失败的
     * 常见原因。
     */
    /** 出站 git 路由的默认值;解析语义见 importing.GitTransportProxy。 */
    public static final String GIT_PROXY_MODE_AUTO = "AUTO";

    private String gitProxy = GIT_PROXY_MODE_AUTO;

    // ---- 本地镜像 ---------------------------------------------------------
    /**
     * 已安装包的本地镜像,以数据库为准。数据库始终是激活时的唯一权威来源;
     * 镜像只是让用户能在磁盘上浏览、备份和用版本工具管理自己的 skill。
     * root 为 null 时在使用时解析为 {@code ./data/skills}(相对后端工作目录),
     * 这样测试可以通过禁用镜像或指向临时目录来保持封闭性。
     */
    private boolean localMirrorEnabled = true;
    private String localMirrorRoot;

    // ---- 访问器 ----------------------------------------------------------
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
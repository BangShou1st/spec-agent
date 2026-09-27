package com.specagent.skill.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 文件名:SkillVersion.java
 *
 * 用途:一个不可变的已安装 Skill 版本。{@code contentHash} 是内容的唯一
 * 身份(规范化 manifest + 文件哈希);版本从不原地修改 —— 升级会创建新的
 * 版本记录。
 */
public record SkillVersion(
        UUID id,
        UUID skillRowId,
        int versionNo,
        String contentHash,
        String manifest,
        String instructions,
        String sourceIdentity,
        int fileCount,
        long totalBytes,
        Instant createdAt) {
}
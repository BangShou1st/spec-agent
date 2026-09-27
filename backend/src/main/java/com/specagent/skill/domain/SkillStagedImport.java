package com.specagent.skill.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 文件名:SkillStagedImport.java
 *
 * 用途:一条已暂存(尚未安装)、等待审阅/安装的 Skill 导入记录。暂存阶段
 * 完成校验、哈希与限额检查,但绝不执行任何内容;安装是另一个独立的显式步骤。
 */
public record SkillStagedImport(
        UUID id,
        SkillSourceKind sourceKind,
        String sourceIdentity,
        String manifest,
        String fileEntries,
        long totalBytes,
        int fileCount,
        String contentHash,
        Status status,
        String rejectedReason,
        Instant createdAt,
        Instant installedAt) {

    public enum Status {
        STAGED,
        READY,
        INSTALLED,
        REJECTED
    }
}
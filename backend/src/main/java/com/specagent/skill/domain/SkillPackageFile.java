package com.specagent.skill.domain;

import java.util.UUID;

/**
 * 文件名:SkillPackageFile.java
 *
 * 用途:已安装 Skill 版本内的一个不可变文件。{@code relativePath} 在导入
 * 时做归一化与路径包含性校验;文本类 kind 的 {@code content} 为文本内容
 * (base64 不进入领域对象 —— 字节由仓储层存储)。
 */
public record SkillPackageFile(
        UUID id,
        UUID versionId,
        String relativePath,
        FileKind kind,
        long sizeBytes,
        String sha256,
        byte[] content) {

    public enum FileKind {
        SKILL_MD("SKILL_MD"),
        TEXT("TEXT"),
        BINARY("BINARY");

        private final String code;

        FileKind(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static FileKind fromCode(String code) {
            for (FileKind kind : values()) {
                if (kind.code.equals(code)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Unknown skill file kind: " + code);
        }
    }
}
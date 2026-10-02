package com.specagent.skill.importing;

import com.specagent.skill.domain.SkillPackageFile;

/**
 * 文件名:SkillSourceFile.java
 *
 * 用途:从 Skill 包来源(ZIP 压缩包或 git 树)提取出的单个、已校验的
 * 内存文件。内容保存在内存中,之后持久化为不可变的包记录 —— 绝不写入
 * 宿主文件系统。
 */
public record SkillSourceFile(String relativePath, byte[] content,
                              SkillPackageFile.FileKind kind) {

    @Override public byte[] content() { return content.clone(); }

    public SkillSourceFile {
        content = content == null ? new byte[0] : content.clone();
    }
}
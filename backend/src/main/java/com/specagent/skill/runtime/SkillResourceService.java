package com.specagent.skill.runtime;

import com.specagent.common.Hashes;
import com.specagent.skill.config.SkillProperties;
import com.specagent.skill.domain.SkillPackageFile;
import com.specagent.skill.importing.SkillImportException;
import com.specagent.skill.registry.SkillQueryService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:SkillResourceService.java
 *
 * 用途:按需读取 Skill 资源,执行严格的路径包含检查并附带溯源信息。一期
 * 只提供文本资源;二进制资源以类型化失败拒绝。读取的前提是该 Skill 版本已
 * 可见/已激活。
 */
@Service
public class SkillResourceService {

    private final SkillQueryService queryService;
    private final SkillProperties properties;

    public SkillResourceService(SkillQueryService queryService, SkillProperties properties) {
        this.queryService = queryService;
        this.properties = properties;
    }

    /**
     * 读取一个已激活 Skill 版本内的指定资源。
     *
     * @param versionId    不可变的已激活版本 id
     * @param relativePath 已归一化、包含性校验的资源路径;包含 SKILL.md,因为
     *                     详情页需要以只读方式展示它
     * @throws SkillImportException 路径穿越、超大、不存在或二进制时抛出
     */
    public ResourceRead readResource(UUID versionId, String relativePath) {
        String normalized = normalizePath(relativePath);
        Optional<SkillPackageFile> file =
                queryService.findPackageFile(versionId, normalized);
        if (file.isEmpty()) {
            throw new SkillResourceRejectedException(
                    "Skill resource not found in activated version: " + normalized);
        }
        SkillPackageFile packageFile = file.get();
        if (packageFile.kind() == SkillPackageFile.FileKind.BINARY) {
            throw new SkillResourceRejectedException(
                    "Skill resource is binary; phase one serves text resources only: "
                            + normalized);
        }
        String text = packageFile.content() == null
                ? "" : new String(packageFile.content(), StandardCharsets.UTF_8);
        boolean truncated = text.length() > properties.getResourceMaxInlineBytes();
        String bounded = truncated
                ? text.substring(0, properties.getResourceMaxInlineBytes()) : text;
        return new ResourceRead(normalized, bounded, truncated, text.length(),
                packageFile.sha256(), versionId.toString());
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            throw new SkillResourceRejectedException("Skill resource path is empty");
        }
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..")
                || normalized.startsWith(".") || normalized.length() > 512) {
            throw new SkillResourceRejectedException(
                    "Skill resource path rejected: " + path);
        }
        // SKILL.md 可以像其他文本资源一样被读取,详情页借此展示 Skill 的真实
        // 内容。读它不会改变执行语义:Skill 的运行仍要走激活流程,指令由
        // 激活动作注入。
        return normalized;
    }

    /**
     * 校验返回内容的哈希与不可变的包哈希一致(溯源自检)。
     */
    public boolean verifies(ResourceRead read) {
        return read.sha256().equals(Hashes.sha256Hex(read.content()));
    }

    public record ResourceRead(String relativePath, String content, boolean truncated,
                               int totalChars, String sha256, String versionId) {
    }
}
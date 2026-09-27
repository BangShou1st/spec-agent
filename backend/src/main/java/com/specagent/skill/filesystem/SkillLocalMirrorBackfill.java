package com.specagent.skill.filesystem;

import com.specagent.skill.domain.Skill;
import com.specagent.skill.domain.SkillPackageFile;
import com.specagent.skill.domain.SkillVersion;
import com.specagent.skill.persistence.SkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文件名:SkillLocalMirrorBackfill.java
 *
 * 用途:本地 skill 镜像的启动回填 —— 从权威数据库重新投影每个已安装
 * skill 的当前版本。这是镜像自愈能力的来源:磁盘上被删除、被修改或丢失的
 * 文件会在下次启动时从 Postgres 重写;镜像功能上线之前安装的 skill(既有
 * 数据库里的全部记录)也会无需任何迁移步骤即出现在磁盘上。幂等且绝不致命。
 */
@Component
public class SkillLocalMirrorBackfill implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SkillLocalMirrorBackfill.class);

    private final SkillRepository repository;
    private final SkillLocalMirror mirror;

    public SkillLocalMirrorBackfill(SkillRepository repository, SkillLocalMirror mirror) {
        this.repository = repository;
        this.mirror = mirror;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            backfill();
        } catch (RuntimeException ex) {
            LOG.warn("Skill local mirror backfill skipped: {}", ex.getMessage());
        }
    }

    void backfill() {
        List<Skill> skills = repository.listSkills();
        int mirrored = 0;
        for (Skill skill : skills) {
            if (skill.currentVersionId() == null) {
                continue;
            }
            SkillVersion version = repository.findVersion(skill.currentVersionId()).orElse(null);
            if (version == null) {
                continue;
            }
            List<SkillPackageFile> files = repository.listPackageFiles(version.id());
            mirror.mirrorPackageFiles(skill.skillId(), version.versionNo(), files);
            mirrored++;
        }
        if (mirrored > 0) {
            LOG.info("Skill local mirror backfilled {} installed skills into {}", mirrored, mirror.root());
        }
    }
}

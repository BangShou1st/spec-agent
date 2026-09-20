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
 * Startup backfill for the local skill mirror: re-projects every installed
 * skill's current version from the authoritative database. This is what
 * makes the mirror self-healing — files deleted, edited or lost on disk are
 * rewritten from Postgres on the next start, and skills installed before the
 * mirror existed (all rows in an existing database) appear on disk without
 * any migration step. Idempotent and never fatal.
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

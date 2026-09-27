package com.specagent.skill.registry;

import com.specagent.skill.domain.Skill;
import com.specagent.skill.domain.SkillPackageFile;
import com.specagent.skill.domain.SkillStagedImport;
import com.specagent.skill.domain.SkillVersion;
import com.specagent.skill.persistence.SkillRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:SkillQueryService.java
 *
 * 用途:权威 Skill 存储之上的只读查询门面。Agent/Brain 绝不直接消费这些
 * 内部接口;本服务为管理/API 视图提供数据,并(经由发现投影)提供有界的
 * 目录读取。
 */
@Service
public class SkillQueryService {

    private final SkillRepository repository;

    public SkillQueryService(SkillRepository repository) {
        this.repository = repository;
    }

    public List<Skill> listSkills() {
        return repository.listSkills();
    }

    public Optional<Skill> findSkill(String skillId) {
        return repository.findSkill(skillId);
    }

    public Optional<Skill> findSkillById(UUID id) {
        return repository.findSkillById(id);
    }

    public Optional<SkillVersion> findVersion(UUID versionId) {
        return repository.findVersion(versionId);
    }

    public List<SkillVersion> listVersions(UUID skillRowId) {
        return repository.listVersions(skillRowId);
    }

    /** 某个版本的已安装文件清单,不含字节负载。 */
    public List<SkillRepository.FileSummary> listFileSummaries(UUID versionId) {
        return repository.listFileSummaries(versionId);
    }

    public Optional<SkillPackageFile> findPackageFile(UUID versionId, String relativePath) {
        return repository.findPackageFile(versionId, relativePath);
    }

    public boolean isSkillEnabled(UUID skillRowId) {
        return repository.findSkillById(skillRowId).map(Skill::enabled).orElse(false);
    }

    public Optional<SkillStagedImport> findStagedImport(UUID stagedImportId) {
        return repository.findStagedImport(stagedImportId);
    }

    public List<SkillStagedImport> listStagedImports() {
        // 只暴露待审阅视图:REJECTED 记录为审计目的保持持久,
        // 但绝不能再作为可操作的待审导入出现。
        return repository.listStagedImports(List.of(
                SkillStagedImport.Status.STAGED,
                SkillStagedImport.Status.READY));
    }
}

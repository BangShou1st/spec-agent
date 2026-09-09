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
 * Query-only facade over the authoritative Skill store. The Agent/Brain never
 * consumes these internals directly; this service powers management/API views
 * and (through the discovery projection) bounded catalog reads.
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

    /** Installed file list for a version, without byte payloads. */
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
        return repository.listStagedImports(List.of(
                SkillStagedImport.Status.STAGED,
                SkillStagedImport.Status.READY,
                SkillStagedImport.Status.REJECTED));
    }
}
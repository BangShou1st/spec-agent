package com.specagent.api.skill;

import com.specagent.skill.domain.Skill;
import com.specagent.skill.importing.SkillImportException;
import com.specagent.skill.registry.SkillImportService;
import com.specagent.skill.registry.SkillQueryService;
import com.specagent.skill.runtime.SkillResourceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Skill management backend API. This is the contract the future Skills UI
 * consumes; it is complete enough that the frontend never has to force a
 * refactor of the core Runtime.
 *
 * <p>Endpoints:<ul>
 *   <li>list + detail + versions + safe file/resource reads;</li>
 *   <li>staged ZIP import, staged git import, staged review + install/reject;</li>
 *   <li>enable / disable / delete;</li>
 *   <li>resource inventory (path + kind + size + hash, never raw content
 *       except through the bounded detail read).</li>
 * </ul>
 * <p>All failures are typed via {@link SkillImportException} /
 * {@link SkillResourceRejectedException} so raw provider/stack details never
 * leak to the client.
 */
@RestController
@RequestMapping("/api/v1/skills")
public class SkillController {

    private final SkillQueryService queryService;
    private final SkillImportService importService;
    private final SkillResourceService resourceService;

    public SkillController(SkillQueryService queryService,
                           SkillImportService importService,
                           SkillResourceService resourceService) {
        this.queryService = queryService;
        this.importService = importService;
        this.resourceService = resourceService;
    }

    // ---- listing / detail ------------------------------------------------

    @GetMapping
    public List<SkillSummaryResponse> listSkills() {
        return queryService.listSkills().stream()
                .map(SkillSummaryResponse::from)
                .toList();
    }

    @GetMapping("/{skillId}")
    public ResponseEntity<SkillDetailResponse> getSkill(@PathVariable String skillId) {
        return queryService.findSkill(skillId)
                .map(skill -> ResponseEntity.ok(SkillDetailResponse.from(skill)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{skillId}/versions")
    public ResponseEntity<List<SkillVersionResponse>> listVersions(@PathVariable String skillId) {
        Optional<Skill> skill = queryService.findSkill(skillId);
        if (skill.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(queryService.listVersions(skill.get().id()).stream()
                .map(version -> new SkillVersionResponse(
                        version.id().toString(), version.versionNo(), version.contentHash(),
                        version.fileCount(), version.totalBytes(), version.createdAt()))
                .toList());
    }

    @GetMapping("/{skillId}/resources")
    public ResponseEntity<List<ResourceSummaryResponse>> listResources(
            @PathVariable String skillId) {
        Optional<Skill> skill = queryService.findSkill(skillId);
        if (skill.isEmpty() || skill.get().currentVersionId() == null) {
            return ResponseEntity.notFound().build();
        }
        List<ResourceSummaryResponse> resources = queryService
                .listFileSummaries(skill.get().currentVersionId()).stream()
                .map(summary -> new ResourceSummaryResponse(
                        summary.relativePath(), summary.kind().code(),
                        summary.sizeBytes(), summary.sha256()))
                .toList();
        return ResponseEntity.ok(resources);
    }

    // ---- staged imports --------------------------------------------------

    @PostMapping("/imports/zip")
    public ResponseEntity<StagedImportResponse> stageZip(@RequestParam("file") MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new SkillImportException("Failed to read uploaded archive: "
                    + ex.getClass().getSimpleName());
        }
        SkillImportService.StagedResult staged = importService.stageZip(bytes);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(StagedImportResponse.from(staged));
    }

    @PostMapping("/imports/git")
    public ResponseEntity<StagedImportResponse> stageGit(
            @RequestBody GitImportRequest request) {
        SkillImportService.StagedResult staged =
                importService.stageGit(request.url(), request.ref());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(StagedImportResponse.from(staged));
    }

    @GetMapping("/imports")
    public List<StagedImportDetailResponse> listStagedImports() {
        return queryService.listStagedImports().stream()
                .map(StagedImportDetailResponse::from)
                .toList();
    }

    @GetMapping("/imports/{stagedImportId}")
    public ResponseEntity<StagedImportDetailResponse> getStagedImport(
            @PathVariable UUID stagedImportId) {
        return queryService.findStagedImport(stagedImportId)
                .map(staged -> ResponseEntity.ok(StagedImportDetailResponse.from(staged)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/imports/{stagedImportId}/install")
    public ResponseEntity<Map<String, Object>> install(@PathVariable UUID stagedImportId) {
        SkillImportService.InstalledResult installed = importService.install(stagedImportId);
        return ResponseEntity.ok(Map.of(
                "skillId", installed.skillId(),
                "skillRowId", installed.skillRowId().toString(),
                "versionId", installed.versionId().toString(),
                "versionNo", installed.versionNo()));
    }

    @PostMapping("/imports/{stagedImportId}/reject")
    public ResponseEntity<Void> reject(@PathVariable UUID stagedImportId,
                                       @RequestBody(required = false) RejectRequest request) {
        importService.rejectStaged(stagedImportId,
                request == null ? null : request.reason());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/imports/{stagedImportId}")
    public ResponseEntity<Void> deleteStaged(@PathVariable UUID stagedImportId) {
        importService.deleteStaged(stagedImportId);
        return ResponseEntity.noContent().build();
    }

    // ---- lifecycle -------------------------------------------------------

    @PostMapping("/{skillId}/enable")
    public ResponseEntity<Void> enable(@PathVariable String skillId) {
        Skill skill = queryService.findSkill(skillId)
                .orElseThrow(() -> new SkillImportException("Skill not found: " + skillId));
        importService.enable(skill.id());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{skillId}/disable")
    public ResponseEntity<Void> disable(@PathVariable String skillId) {
        Skill skill = queryService.findSkill(skillId)
                .orElseThrow(() -> new SkillImportException("Skill not found: " + skillId));
        importService.disable(skill.id());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{skillId}")
    public ResponseEntity<Void> delete(@PathVariable String skillId) {
        Skill skill = queryService.findSkill(skillId)
                .orElseThrow(() -> new SkillImportException("Skill not found: " + skillId));
        importService.delete(skill.id());
        return ResponseEntity.noContent().build();
    }

    // ---- safe resource read ----------------------------------------------

    /**
     * Bounded safe resource detail read: latest installed version, path
     * containment enforced server-side, text-only in phase one.
     */
    @GetMapping("/{skillId}/resources/read")
    public ResponseEntity<ResourceReadResponse> readResource(
            @PathVariable String skillId, @RequestParam("path") String path) {
        Skill skill = queryService.findSkill(skillId)
                .orElseThrow(() -> new SkillImportException("Skill not found: " + skillId));
        if (skill.currentVersionId() == null) {
            throw new SkillImportException("Skill has no installed version: " + skillId);
        }
        SkillResourceService.ResourceRead read =
                resourceService.readResource(skill.currentVersionId(), path);
        return ResponseEntity.ok(new ResourceReadResponse(
                read.relativePath(), read.content(), read.truncated(),
                read.totalChars(), read.sha256(), read.versionId()));
    }

    // ---- DTOs ------------------------------------------------------------

    public record SkillSummaryResponse(String skillId, String name, String description,
                                       String sourceKind, String versionId,
                                       boolean enabled, Instant createdAt) {
        static SkillSummaryResponse from(Skill skill) {
            return new SkillSummaryResponse(skill.skillId(), skill.name(),
                    skill.description(), skill.sourceKind().code(),
                    skill.currentVersionId() == null ? null : skill.currentVersionId().toString(),
                    skill.enabled(), skill.createdAt());
        }
    }

    public record SkillDetailResponse(String skillId, String name, String description,
                                      String sourceKind, String sourceIdentity,
                                      String versionId, boolean enabled,
                                      Instant createdAt, Instant updatedAt) {
        static SkillDetailResponse from(Skill skill) {
            return new SkillDetailResponse(skill.skillId(), skill.name(),
                    skill.description(), skill.sourceKind().code(),
                    skill.sourceIdentity(),
                    skill.currentVersionId() == null ? null : skill.currentVersionId().toString(),
                    skill.enabled(), skill.createdAt(), skill.updatedAt());
        }
    }

    public record SkillVersionResponse(String id, int versionNo, String contentHash,
                                       int fileCount, long totalBytes, Instant createdAt) {
    }

    public record ResourceSummaryResponse(String path, String kind, long sizeBytes,
                                          String sha256) {
    }

    public record StagedImportResponse(UUID stagedImportId, String name, String description,
                                       String contentHash, int fileCount, long totalBytes) {
        static StagedImportResponse from(SkillImportService.StagedResult staged) {
            return new StagedImportResponse(staged.stagedImportId(), staged.name(),
                    staged.description(), staged.contentHash(), staged.fileCount(),
                    staged.totalBytes());
        }
    }

    public record StagedImportDetailResponse(UUID stagedImportId, String sourceKind,
                                             String sourceIdentity, String manifest,
                                             long totalBytes, int fileCount,
                                             String contentHash, String status,
                                             String rejectedReason, Instant createdAt) {
        static StagedImportDetailResponse from(com.specagent.skill.domain.SkillStagedImport staged) {
            return new StagedImportDetailResponse(staged.id(), staged.sourceKind().code(),
                    staged.sourceIdentity(), staged.manifest(), staged.totalBytes(),
                    staged.fileCount(), staged.contentHash(), staged.status().name(),
                    staged.rejectedReason(), staged.createdAt());
        }
    }

    public record ResourceReadResponse(String relativePath, String content, boolean truncated,
                                       int totalChars, String sha256, String versionId) {
    }

    public record GitImportRequest(String url, String ref) {
    }

    public record RejectRequest(String reason) {
    }
}
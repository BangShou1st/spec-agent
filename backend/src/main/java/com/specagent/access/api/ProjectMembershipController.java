package com.specagent.access.api;

import com.specagent.access.ProjectRole;
import com.specagent.access.identity.ProjectMemberManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class ProjectMembershipController {
    private final ProjectMemberManagementService service;
    public ProjectMembershipController(ProjectMemberManagementService service) {this.service=service;}
    public record Grant(@NotNull ProjectRole role) {}
    @GetMapping("/members") public List<ProjectMemberManagementService.Member> members(@PathVariable UUID projectId) {
        return service.members(projectId);
    }
    @PutMapping("/members/{userId}") public void grant(@PathVariable UUID projectId,@PathVariable UUID userId,
                                                      @Valid @RequestBody Grant body) {
        service.grant(projectId,userId,body.role());
    }
    @DeleteMapping("/members/{userId}") public void remove(@PathVariable UUID projectId,@PathVariable UUID userId) {
        service.remove(projectId,userId);
    }
    @GetMapping("/access") public Map<String,Object> capabilities(@PathVariable UUID projectId) {
        return Map.of("capabilities",service.capabilities(projectId));
    }
}

package com.specagent.access.api;

import com.specagent.access.OrgRole;
import com.specagent.access.identity.OrganizationManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {
    private final OrganizationManagementService service;
    public OrganizationController(OrganizationManagementService service) {this.service=service;}
    public record Create(@NotBlank String name) {}
    public record Membership(@NotNull UUID userId,@NotNull OrgRole role) {}
    public record Role(@NotNull OrgRole role) {}
    @PostMapping public OrganizationManagementService.Organization create(@Valid @RequestBody Create body) {
        return service.create(body.name());
    }
    @GetMapping("/{orgId}/members") public List<OrganizationManagementService.Member> members(@PathVariable UUID orgId) {
        return service.members(orgId);
    }
    @PostMapping("/{orgId}/members") public void add(@PathVariable UUID orgId,@Valid @RequestBody Membership body) {
        service.add(orgId,body.userId(),body.role());
    }
    @PatchMapping("/{orgId}/members/{userId}") public void change(@PathVariable UUID orgId,@PathVariable UUID userId,
                                                               @Valid @RequestBody Role body) {
        service.change(orgId,userId,body.role());
    }
    @DeleteMapping("/{orgId}/members/{userId}") public void remove(@PathVariable UUID orgId,@PathVariable UUID userId) {
        service.remove(orgId,userId);
    }
}

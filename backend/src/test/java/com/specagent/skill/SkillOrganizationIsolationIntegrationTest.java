package com.specagent.skill;

import com.specagent.access.*;
import com.specagent.access.identity.IdentityRepository;
import com.specagent.skill.discovery.SkillDiscoveryContext;
import com.specagent.skill.discovery.SkillDiscoveryService;
import com.specagent.skill.domain.Skill;
import com.specagent.skill.domain.SkillPackageFile;
import com.specagent.skill.importing.SkillSourceFile;
import com.specagent.skill.persistence.SkillRepository;
import com.specagent.skill.registry.SkillImportService;
import com.specagent.skill.registry.SkillQueryService;
import com.specagent.skill.runtime.SkillResourceService;
import com.specagent.workspace.project.ProjectService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL facts; actors are explicitly bound, not an OIDC browser test. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SkillOrganizationIsolationIntegrationTest {
    @Autowired IdentityRepository identities;
    @Autowired SkillImportService imports;
    @Autowired SkillQueryService queries;
    @Autowired SkillRepository repository;
    @Autowired SkillResourceService resources;
    @Autowired SkillDiscoveryService discovery;
    @Autowired ProjectService projects;
    @Autowired JdbcTemplate jdbc;

    private UUID org() {
        UUID id=UUID.randomUUID();
        identities.insertOrganizationIfAbsent(id,"skill-org","STANDARD");
        return id;
    }
    private ActorContext actor(UUID org, OrgRole role) {
        UUID user=UUID.randomUUID();
        identities.insertUserIfAbsent(user,"skill-user");
        identities.upsertMembership(org,user,role);
        return ActorContext.oidc(user,org);
    }
    private <T> T as(ActorContext actor, Supplier<T> action) {
        try(var ignored=ActorContextHolder.bind(actor)) {return action.get();}
    }
    private SkillImportService.StagedResult stage(ActorContext actor,String name) {
        String markdown="---\nname: "+name+"\ndescription: scoped test\n---\nPrivate instructions.";
        return as(actor,()->imports.stageBuiltin(name,markdown,
                List.of(new SkillSourceFile("SKILL.md",markdown.getBytes(StandardCharsets.UTF_8),
                        SkillPackageFile.FileKind.TEXT)),markdown.getBytes(StandardCharsets.UTF_8).length));
    }
    private SkillImportService.InstalledResult install(ActorContext actor,String name) {
        var staged=stage(actor,name);
        return as(actor,()-> {
            var installed=imports.install(staged.stagedImportId());
            imports.enable(installed.skillRowId());
            return installed;
        });
    }

    @Test void candidateAndDirectReadsCannotEnumerateAnotherOrganization() {
        var a=actor(org(),OrgRole.OWNER);var b=actor(org(),OrgRole.OWNER);
        var sa=install(a,"skill-a");var sb=install(b,"skill-b");
        as(a,()-> {
            assertThat(queries.listSkills()).extracting(Skill::id).containsExactly(sa.skillRowId());
            assertThat(queries.findSkill(sb.skillId())).isEmpty();
            assertThat(queries.findSkillById(sb.skillRowId())).isEmpty();
            assertThat(queries.findVersion(sb.versionId())).isEmpty();
            assertThat(queries.listVersions(sb.skillRowId())).isEmpty();
            assertThat(queries.listFileSummaries(sb.versionId())).isEmpty();
            assertThat(queries.findPackageFile(sb.versionId(),"SKILL.md")).isEmpty();
            assertThat(discovery.discover(SkillDiscoveryContext.empty()).entries())
                    .extracting(com.specagent.skill.discovery.SkillCatalogEntry::skillId)
                    .containsExactly(sa.skillId());
            return null;
        });
    }

    @Test void sameBytesAndEnabledNameAreIndependentAcrossOrganizations() {
        var a=actor(org(),OrgRole.OWNER);var b=actor(org(),OrgRole.OWNER);
        var sa=install(a,"same-package");var sb=install(b,"same-package");
        assertThat(sa.skillRowId()).isNotEqualTo(sb.skillRowId());
        assertThat(sa.skillId()).isNotEqualTo(sb.skillId());
        assertThat(sa.versionId()).isNotEqualTo(sb.versionId());
        assertThat(as(a,()->queries.findVersion(sa.versionId()).orElseThrow().contentHash()))
                .isEqualTo(as(b,()->queries.findVersion(sb.versionId()).orElseThrow().contentHash()));
        assertThat(as(a,()->resources.readResource(sa.versionId(),"SKILL.md").content()))
                .isEqualTo(as(b,()->resources.readResource(sb.versionId(),"SKILL.md").content()));
    }

    @Test void identicalReinstallReusesOnlyItsOwnImmutableVersion() {
        var owner=actor(org(),OrgRole.OWNER);
        var original=install(owner,"idempotent");
        var staged=stage(owner,"idempotent");
        var repeated=as(owner,()->imports.install(staged.stagedImportId()));
        assertThat(repeated.skillRowId()).isEqualTo(original.skillRowId());
        assertThat(repeated.versionId()).isEqualTo(original.versionId());
        assertThat(repeated.versionNo()).isEqualTo(original.versionNo());
        assertThat(as(owner,()->queries.listVersions(original.skillRowId()))).hasSize(1);
    }

    @Test void stagingAndItsBytesFollowOrganizationBeforeInstallOrDelete() {
        var a=actor(org(),OrgRole.OWNER);var b=actor(org(),OrgRole.OWNER);
        var staged=stage(a,"staging");
        as(b,()-> {
            assertThat(queries.listStagedImports()).isEmpty();
            assertThat(queries.findStagedImport(staged.stagedImportId())).isEmpty();
            assertThat(repository.listStagedFiles(staged.stagedImportId())).isEmpty();
            return null;
        });
        assertThatThrownBy(()->as(b,()->imports.install(staged.stagedImportId())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test void memberCanReadSharedSkillButCannotDisableIt() {
        UUID org=org();var owner=actor(org,OrgRole.OWNER);var member=actor(org,OrgRole.MEMBER);
        var installed=install(owner,"shared");
        assertThat(as(member,()->resources.readResource(installed.versionId(),"SKILL.md").content()))
                .contains("Private instructions");
        assertThatThrownBy(()->as(member,()-> {imports.disable(installed.skillRowId());return null;}))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test void memberCannotStageConfiguration() {
        var member=actor(org(),OrgRole.MEMBER);
        assertThatThrownBy(()->stage(member,"forbidden"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test void membershipRevocationRejectsPreviouslyKnownVersionAndCatalog() {
        var owner=actor(org(),OrgRole.OWNER);var installed=install(owner,"revoked");
        identities.updateMembershipStatus(owner.orgId(),owner.userId(),"DISABLED");
        assertThatThrownBy(()->as(owner,()->queries.findVersion(installed.versionId())))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->as(owner,queries::listSkills)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void currentVersionCannotPointToAnotherSkillEvenWithinSameOrg() {
        var owner=actor(org(),OrgRole.OWNER);
        var first=install(owner,"first");var second=install(owner,"second");
        assertThatThrownBy(()->jdbc.update("UPDATE skills SET current_version_id=? WHERE id=?",
                second.versionId(),first.skillRowId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("skill_current_version_parent");
    }

    @Test void activationCannotBindPrivateVersionToAnotherOrganizationsProject() {
        var a=actor(org(),OrgRole.OWNER);var b=actor(org(),OrgRole.OWNER);
        var skill=install(a,"private");
        UUID project=as(b,()->projects.createProject("foreign-project").id());
        assertThatThrownBy(()->jdbc.update("INSERT INTO skill_activations "
                +"(id,org_id,project_id,skill_id,version_id,source_identity,content_hash,created_at) "
                +"VALUES(?,?,?,?,?,?,?,now())",UUID.randomUUID(),b.orgId(),project,skill.skillId(),
                skill.versionId(),"test","test"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("skill_activation_skill_org");
    }

    @Test void activationCannotMislabelAnotherSkillVersionAsAnAllowedSkill() {
        var owner=actor(org(),OrgRole.OWNER);
        var first=install(owner,"allowed");var second=install(owner,"other");
        UUID project=as(owner,()->projects.createProject("activation-parent").id());
        assertThatThrownBy(()->jdbc.update("INSERT INTO skill_activations "
                +"(id,org_id,project_id,skill_id,version_id,source_identity,content_hash,created_at) "
                +"VALUES(?,?,?,?,?,?,?,now())",UUID.randomUUID(),owner.orgId(),project,first.skillId(),
                second.versionId(),"test","test"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("skill_activation_version_parent");
    }
}

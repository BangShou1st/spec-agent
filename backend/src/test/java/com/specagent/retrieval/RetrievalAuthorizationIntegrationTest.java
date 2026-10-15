package com.specagent.retrieval;

import com.specagent.access.*;
import com.specagent.access.identity.IdentityRepository;
import com.specagent.retrieval.protocol.*;
import com.specagent.retrieval.search.HybridRetriever;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RetrievalAuthorizationIntegrationTest {
    @Autowired IdentityRepository identities;
    @Autowired ProjectService projects;
    @Autowired RetrievalStore store;
    @Autowired HybridRetriever legacy;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.specagent.assistant.conversation.GlobalAssistantConversationService conversations;
    @Autowired com.specagent.workspace.node.NodeService nodes;

    private ActorContext actor(UUID org) {
        UUID user=UUID.randomUUID();
        identities.insertUserIfAbsent(user,"retrieval-user");
        identities.upsertMembership(org,user,OrgRole.MEMBER);
        return ActorContext.oidc(user,org);
    }
    private UUID org() {
        UUID id=UUID.randomUUID();
        identities.insertOrganizationIfAbsent(id,"retrieval-org","STANDARD");
        return id;
    }
    private Project project(ActorContext actor) {
        try(var scope=ActorContextHolder.bind(actor)) {return projects.createProject("private-retrieval-"+UUID.randomUUID());}
    }
    private RetrievalQuery query(Project p) {
        return new RetrievalQuery(p.id(),null,null,"EXPLICIT_SEARCH","private",List.of(RetrievalScope.PROJECT),8,8000,Set.of(),Set.of());
    }
    private RetrievalWire.Search grant(ActorContext actor,Project p) {
        try(var scope=ActorContextHolder.bind(actor)) {return store.issueProject(query(p),UUID.randomUUID(),"CONTEXT_PROJECTION",Set.of());}
    }
    private void stale(RetrievalWire.Search grant) {
        assertThatThrownBy(()->store.guard(grant)).isInstanceOf(IllegalStateException.class).hasMessage("STALE_SCOPE_GRANT");
    }

    private UUID gaRun(ActorContext actor,long epoch) {
        UUID thread;
        try(var scope=ActorContextHolder.bind(actor)) {thread=conversations.createThread().id();}
        UUID run=UUID.randomUUID();
        jdbc.update("INSERT INTO global_assistant_runs(id,thread_id,status) VALUES(?,?,'RUNNING')",run,thread);
        jdbc.update("INSERT INTO ga_run_authorizations(run_id,actor_user_id,actor_org_id,actor_source) VALUES(?,?,?,'OIDC')",
                run,actor.userId(),actor.orgId());
        jdbc.update("""
                INSERT INTO ga_executions(run_id,thread_id,model_binding_id,provider,model,settings_revision,
                    execution_epoch,lease_id,deadline)
                VALUES(?,?,?,'OPENCODE_ZEN','fake','fixture',?,?,now()+interval '3 minutes')
                """,run,thread,UUID.randomUUID(),epoch,UUID.randomUUID());
        return run;
    }

    @Test void helpGrantCapturesCurrentGaEpochAndRejectsLaterTakeover() {
        var actor=actor(org());UUID run=gaRun(actor,3);
        RetrievalWire.Search request;
        try(var scope=ActorContextHolder.bind(actor)) {request=store.issueHelp("项目",3,run);}
        assertThat(request.workload().executionEpoch()).isEqualTo(3);
        assertThat(store.guard(request).projectId()).isNull();
        jdbc.update("UPDATE ga_executions SET execution_epoch=4 WHERE run_id=?",run);
        assertThatThrownBy(()->store.guard(request)).isInstanceOf(IllegalStateException.class).hasMessage("STALE_WORKLOAD");
    }

    @Test void sameOrganizationCannotIssueHelpGrantForAnotherUsersRun() {
        UUID org=org();var owner=actor(org);UUID run=gaRun(owner,1);var outsider=actor(org);
        try(var scope=ActorContextHolder.bind(outsider)) {
            assertThatThrownBy(()->store.issueHelp("项目",3,run)).isInstanceOf(IllegalStateException.class).hasMessage("STALE_WORKLOAD");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM retrieval_scope_grants WHERE workload_id=?",Integer.class,run)).isZero();
    }

    @Test void databasePreventsCorruptedCorpusAffinity() {
        var owner=actor(org());var visible=project(owner);
        var foreignOwner=actor(org());var foreign=project(foreignOwner);
        try(var scope=ActorContextHolder.bind(foreignOwner)) {
            nodes.createRootNode(foreign.id(),foreign.activeRouteId(),"private hidden canonical source",null,List.of(),true);
        }
        assertThatThrownBy(()->jdbc.update("UPDATE retrieval_entries SET corpus_id=? WHERE project_id=?",visible.id(),foreign.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("ck_retrieval_entries_corpus");
    }

    @Test void rpcRebuildsGrantActorWithoutRequestThreadOrLocalFallback() {
        var actor=actor(org());var p=project(actor);var request=grant(actor,p);
        assertThat(ActorContextHolder.current()).isNull();
        assertThat(store.guard(request).projectId()).isEqualTo(p.id());
        var row=jdbc.queryForMap("SELECT actor_user_id,actor_org_id,actor_source FROM retrieval_scope_grants WHERE id=?",request.scopeGrant().grantId());
        assertThat(row.get("actor_user_id")).isEqualTo(actor.userId());
        assertThat(row.get("actor_org_id")).isEqualTo(actor.orgId());
        assertThat(row.get("actor_source")).isEqualTo("OIDC");
    }

    @Test void sameOrgNoProjectRoleCannotIssueGrantOrReadJavaCandidates() {
        UUID org=org();var p=project(actor(org));var outsider=actor(org);
        try(var scope=ActorContextHolder.bind(outsider)) {
            assertThatThrownBy(()->store.issueProject(query(p),UUID.randomUUID(),"CONTEXT_PROJECTION",Set.of()))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(()->legacy.retrieve(query(p))).isInstanceOf(AccessDeniedException.class);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM retrieval_index_heads WHERE corpus_id=?",Integer.class,p.id())).isZero();
    }

    @Test void otherOrganizationCannotIssueGrantEvenAsProjectCreatorElsewhere() {
        var p=project(actor(org()));var outsider=actor(org());project(outsider);
        assertThatThrownBy(()->grant(outsider,p)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void removedProjectRoleInvalidatesPreviouslyIssuedGrant() {
        var actor=actor(org());var p=project(actor);var request=grant(actor,p);
        jdbc.update("DELETE FROM project_members WHERE project_id=? AND user_id=?",p.id(),actor.userId());
        stale(request);
    }

    @Test void disabledOrganizationMembershipInvalidatesPreviouslyIssuedGrant() {
        var actor=actor(org());var p=project(actor);var request=grant(actor,p);
        jdbc.update("UPDATE organization_members SET status='DISABLED' WHERE org_id=? AND user_id=?",actor.orgId(),actor.userId());
        stale(request);
    }

    @Test void disabledUserInvalidatesPreviouslyIssuedGrant() {
        var actor=actor(org());var p=project(actor);var request=grant(actor,p);
        jdbc.update("UPDATE app_users SET status='DISABLED' WHERE id=?",actor.userId());
        stale(request);
    }
}

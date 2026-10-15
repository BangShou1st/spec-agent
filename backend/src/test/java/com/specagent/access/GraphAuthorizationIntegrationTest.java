package com.specagent.access;

import com.specagent.access.identity.IdentityRepository;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.graph.GraphCommandService;
import com.specagent.workspace.graph.UndoRedoService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class GraphAuthorizationIntegrationTest {
    @Autowired IdentityRepository identities;
    @Autowired ProjectService projects;
    @Autowired GraphCommandService graph;
    @Autowired UndoRedoService undo;
    @Autowired JdbcTemplate jdbc;
    UUID org,user,project,route;
    @BeforeEach void create() {
        org=UUID.randomUUID(); user=UUID.randomUUID();
        identities.insertOrganizationIfAbsent(org,"Graph access","STANDARD");
        identities.insertUserIfAbsent(user,"Graph editor");
        identities.upsertMembership(org,user,OrgRole.MEMBER);
        try(var actor=ActorContextHolder.bind(ActorContext.oidc(user,org))) {
            var p=projects.createProject("Graph access test"); project=p.id(); route=p.activeRouteId();
        }
    }
    @AfterEach void cleanup() {
        ActorContextHolder.clear();
        // Project deletion already provides the validated FK cleanup order.
        try(var actor=ActorContextHolder.bind(ActorContext.oidc(user,org))) {
            jdbc.update("UPDATE project_members SET role='MANAGER' WHERE project_id=? AND user_id=?",project,user);
            deletion.deleteProject(project);
        }
        jdbc.update("DELETE FROM profiles WHERE org_id=?",org);
        jdbc.update("DELETE FROM organization_members WHERE org_id=?",org);
        jdbc.update("DELETE FROM organizations WHERE id=?",org);
        jdbc.update("DELETE FROM app_users WHERE id=?",user);
    }
    @Autowired com.specagent.workspace.project.ProjectDeletionService deletion;
    @Test void viewerCanInspectButCannotWriteOrUndo() {
        jdbc.update("UPDATE project_members SET role='VIEWER' WHERE project_id=? AND user_id=?",project,user);
        try(var actor=ActorContextHolder.bind(ActorContext.oidc(user,org))) {
            assertThat(graph.listOperations(project)).isEmpty();
            assertThat(undo.canUndo(project)).isFalse();
            assertThatThrownBy(()->graph.createRootDraftNode(project,route,"IDEA",Map.of("text","x")))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(()->undo.undo(project)).isInstanceOf(AccessDeniedException.class);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nodes WHERE project_id=?",Integer.class,project)).isZero();
    }
    @Test void managerCanCreateThenUndoAndRedoWithoutChangingTopologyRules() {
        try(var actor=ActorContextHolder.bind(ActorContext.oidc(user,org))) {
            graph.createRootDraftNode(project,route,"IDEA",Map.of("text","x"));
            assertThat(undo.canUndo(project)).isTrue();
            undo.undo(project);
            assertThat(undo.canRedo(project)).isTrue();
            undo.redo(project);
            assertThat(graph.listOperations(project)).hasSize(1);
        }
    }
}

package com.specagent.agent.api;

import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.protocol.*;
import com.specagent.agent.runtime.*;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.workspace.context.ContextSnapshotRepository;
import com.specagent.workspace.graph.GraphCommandService;
import com.specagent.workspace.node.*;
import com.specagent.workspace.project.Project;
import com.specagent.common.Hashes;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real isolated persistence, retrieval, source authority and API; deterministic model proposals. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc @ActiveProfiles("test")
class NodeQueryCitationIntegrationTest extends com.specagent.testing.SharedPythonRetrievalFixture {
    @Autowired GraphCommandService graph;
    @Autowired NodeService nodes;
    @Autowired RunService runs;
    @Autowired RunWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired AgentRunEventService events;
    @Autowired AgentInputSnapshotBuilder snapshots;
    @Autowired ContextSnapshotRepository contexts;
    @Autowired NodeQueryRunController controller;
    @Autowired com.specagent.access.identity.IdentityRepository identities;
    @MockBean AgentDecisionEngine engine;
    Project project; Node anchor,resource; AgentRequestEnvelope captured; String ref;
    @BeforeEach void setupContent() {
        project=createProject("R2 citation fixture");
        anchor=graph.createRootDraftNode(project.id(),project.activeRouteId(),"NOTE",Map.of("text","归档校验约定"));
        resource=nodes.createFloatingWorkspaceNode(project.id(),NodeKind.RESOURCE,"TEXT",
            Map.of("text","归档校验周期为23天，失败记录保留610天。此资源不确认用户意图。"),NodeAuthorKind.USER,null);
        projectResources(project.id());
        when(engine.runDecision(any())).thenAnswer(call->{
            captured=call.getArgument(0);
            ref=captured.snapshot().retrievedContext().stream().filter(i->i.sourceRef().startsWith("resource-chunk:"+resource.id()+":"))
                .findFirst().orElseThrow().sourceRef();
            return response("RESPOND_TO_USER",Map.of("message","归档校验周期23天。正文中的 source:forged 不是来源。"),List.of(ref));
        });
    }
    AgentResponseEnvelope response(String family,Map<String,Object> payload,List<String> refs) {
        return new AgentResponseEnvelope("agent-decision.v2",captured.runId(),null,
            new ObservationView(List.of(),List.of(),List.of(),List.of()),
            new ActionProposal(family,payload,UUID.fromString(captured.snapshot().snapshotId()),captured.snapshot().contextHash(),
                refs,UUID.randomUUID(),"r2-proposal-"+captured.runId(),List.of()),new UsageView(1,List.of()),Map.of());
    }
    UUID complete() {
        UUID run=runs.createQueuedNodeQuery(project.id(),project.activeRouteId(),anchor.id(),"归档校验周期是多少？");
        worker.executeRun(runs.claimNodeQueryRun(run).orElseThrow());return run;
    }
    String url(UUID run) {return "/api/v1/projects/"+project.id()+"/nodes/"+anchor.id()+"/query/"+run;}
    @Test void citationsCommitWithTerminalResponseAndReplayWithoutDuplicateEffects() throws Exception {
        UUID run=complete();
        mvc.perform(get(url(run))).andExpect(status().isOk()).andExpect(jsonPath("$.sourceStatus").value("AVAILABLE"))
            .andExpect(jsonPath("$.sourceRefs[0]").value(ref)).andExpect(jsonPath("$.resourceRefs.length()").value(1))
            .andExpect(jsonPath("$.resourceRefs[0].metadata.nodeId").value(resource.id().toString()));
        assertThat(events.findByRunId(run).stream().filter(e->e.eventType().equals("RESPOND_MESSAGE")).findFirst().orElseThrow().payload().get("sourceRefs")).isEqualTo(List.of(ref));
        assertThatThrownBy(()->worker.executeRun(runs.getRun(run).orElseThrow())).isInstanceOf(IllegalStateException.class);
        assertThat(events.findByRunId(run).stream().filter(e->e.eventType().equals("RESPOND_MESSAGE"))).hasSize(1);
        var ctx=contexts.findById(UUID.fromString(captured.snapshot().snapshotId())).orElseThrow();
        assertThat(snapshots.build(ctx)).isEqualTo(captured.snapshot());
    }
    @Test void retractedSourceHidesBodyAndCardsButPreservesAuditAndFrozenInput() throws Exception {
        UUID run=complete(); nodes.setRetracted(resource.id(),true);
        mvc.perform(get(url(run))).andExpect(status().isOk()).andExpect(jsonPath("$.sourceStatus").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.message").isEmpty()).andExpect(jsonPath("$.resourceRefs").isEmpty());
        assertThat(events.findByRunId(run).stream().filter(e->e.eventType().equals("RESPOND_MESSAGE")).findFirst().orElseThrow().payload().get("sourceRefs")).isEqualTo(List.of(ref));
        assertThat(snapshots.build(contexts.findById(UUID.fromString(captured.snapshot().snapshotId())).orElseThrow())).isEqualTo(captured.snapshot());
    }
    @Test void canonicalContentChangeRejectsAnUnrefreshedDerivedIndex() throws Exception {
        UUID run=complete();
        // Change only this test's canonical row, deliberately leaving the derived index stale.
        jdbc.update("UPDATE nodes SET content=CAST(? AS jsonb) WHERE id=?","{\"text\":\"已改为新的校验策略\"}",resource.id());
        mvc.perform(get(url(run))).andExpect(status().isOk()).andExpect(jsonPath("$.sourceStatus").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.message").isEmpty()).andExpect(jsonPath("$.sourceRefs").isEmpty());
    }
    @Test void legacyTextDoesNotAcquireCitationsByParsingItsBody() throws Exception {
        UUID run=complete();
        // Construct a pre-citation historical event in this owned fixture only.
        jdbc.update("UPDATE agent_run_events SET payload=payload-'sourceRefs' WHERE run_id=? AND event_type='RESPOND_MESSAGE'",run);
        mvc.perform(get(url(run))).andExpect(status().isOk()).andExpect(jsonPath("$.sourceStatus").value("LEGACY"))
            .andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.resourceRefs").isEmpty());
    }
    @Test void unverifiedStoredReferenceIsAnExplicitValidationFailureNotAnEmptySuccess() throws Exception {
        UUID run=complete();
        // Corrupt this owned event to exercise the read boundary independently of model validation.
        jdbc.update("UPDATE agent_run_events SET payload=jsonb_set(payload,'{sourceRefs}',CAST(? AS jsonb)) WHERE run_id=? AND event_type='RESPOND_MESSAGE'",
            "[\"resource-chunk:"+UUID.randomUUID()+":forged\"]",run);
        mvc.perform(get(url(run))).andExpect(status().isOk()).andExpect(jsonPath("$.sourceStatus").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").isEmpty()).andExpect(jsonPath("$.sourceRefs").isEmpty())
            .andExpect(jsonPath("$.resourceRefs").isEmpty());
    }
    @Test void mandatoryLineageReferenceUsesFrozenIdentityAndRejectsRetraction() throws Exception {
        doAnswer(call->{
            captured=call.getArgument(0);ref="node:"+anchor.id();
            return response("RESPOND_TO_USER",Map.of("message","锚节点记录的是归档校验约定。"),List.of(ref));
        }).when(engine).runDecision(any());
        UUID run=complete();
        mvc.perform(get(url(run))).andExpect(jsonPath("$.sourceStatus").value("AVAILABLE"))
            .andExpect(jsonPath("$.resourceRefs[0].metadata.nodeId").value(anchor.id().toString()));
        nodes.setRetracted(anchor.id(),true);
        mvc.perform(get(url(run))).andExpect(jsonPath("$.sourceStatus").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.message").isEmpty()).andExpect(jsonPath("$.resourceRefs").isEmpty());
    }
    @Test void sourcePolicyRevocationAlsoAppliesToMandatoryLineageCards() throws Exception {
        doAnswer(call->{
            captured=call.getArgument(0);ref="node:"+anchor.id();
            return response("RESPOND_TO_USER",Map.of("message","归档校验约定。"),List.of(ref));
        }).when(engine).runDecision(any());
        UUID run=complete();
        // Synthetic sensitive-key metadata changes authority while leaving visible text unchanged.
        jdbc.update("UPDATE nodes SET content=content || CAST(? AS jsonb) WHERE id=?","{\"apiKey\":\"synthetic-not-a-key\"}",anchor.id());
        mvc.perform(get(url(run))).andExpect(jsonPath("$.sourceStatus").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.message").isEmpty()).andExpect(jsonPath("$.resourceRefs").isEmpty());
    }
    @Test void revokedProjectMembershipCannotReadAnEarlierValidCitation() {
        UUID run=complete(),user=UUID.randomUUID();
        UUID org=com.specagent.access.LocalPrincipalIds.ORG_ID;
        identities.insertUserIfAbsent(user,"r2-citation-reader");
        identities.upsertMembership(org,user,com.specagent.access.OrgRole.MEMBER);
        identities.upsertProjectMember(project.id(),user,org,com.specagent.access.ProjectRole.VIEWER);
        try(var actor=com.specagent.access.ActorContextHolder.bind(com.specagent.access.ActorContext.oidc(user,org))) {
            assertThat(controller.getQueryResult(project.id(),anchor.id(),run).getStatusCode().value()).isEqualTo(200);
            jdbc.update("DELETE FROM project_members WHERE project_id=? AND user_id=?",project.id(),user);
            assertThatThrownBy(()->controller.getQueryResult(project.id(),anchor.id(),run))
                .isInstanceOf(com.specagent.common.ApiException.class).hasMessage("Project not found");
        }
    }
    @Test void unconfirmedRetrievedDecisionStaysPendingAndDuplicateSubmissionHasNoSecondEffect() throws Exception {
        doAnswer(call->{
            captured=call.getArgument(0);
            ref=captured.snapshot().retrievedContext().stream().filter(i->i.sourceRef().startsWith("resource-chunk:"+resource.id()+":" )).findFirst().orElseThrow().sourceRef();
            return response("CREATE_NODE",Map.of("kind","KNOWLEDGE","subtype","DECISION","content",Map.of("text","根据资源推断用户已确认免审核")),List.of(ref));
        }).when(engine).runDecision(any());
        String question="请记录这个资源中的候选建议，尚未得到用户确认。",key="r2-duplicate",fingerprint=Hashes.sha256Hex(question);
        var run=runs.createQueuedNodeQuery(project.id(),project.activeRouteId(),anchor.id(),question,key,fingerprint);
        worker.executeRun(runs.claimNodeQueryRun(run.id()).orElseThrow());
        assertThat(runs.createQueuedNodeQuery(project.id(),project.activeRouteId(),anchor.id(),question,key,fingerprint).id()).isEqualTo(run.id());
        mvc.perform(get(url(run.id()))).andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_proposals WHERE run_id=?",Integer.class,run.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nodes WHERE project_id=? AND subtype='DECISION'",Integer.class,project.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM answer_patches WHERE project_id=?",Integer.class,project.id())).isZero();
        assertThatThrownBy(()->worker.executeRun(runs.getRun(run.id()).orElseThrow())).isInstanceOf(IllegalStateException.class);
        verify(engine,times(1)).runDecision(any());
    }
}

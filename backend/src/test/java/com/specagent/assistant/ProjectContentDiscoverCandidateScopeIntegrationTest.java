package com.specagent.assistant;

import com.specagent.access.ActorContext;
import com.specagent.access.ActorContextHolder;
import com.specagent.access.AuthorizationGuard;
import com.specagent.access.OrgRole;
import com.specagent.access.identity.IdentityRepository;
import com.specagent.assistant.tool.GaRetrievalAccess;
import com.specagent.assistant.tool.ProjectContentDiscoverCapability;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityResult;
import com.specagent.common.Hashes;
import com.specagent.retrieval.RetrievalQuery;
import com.specagent.retrieval.protocol.*;
import static com.specagent.retrieval.protocol.RetrievalWire.*;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectRepository;
import com.specagent.workspace.project.ProjectService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:ProjectContentDiscoverCandidateScopeIntegrationTest.java
 *
 * 用途:GA 隐式项目内容检索({@code project.content.discover} 无 projectId 分支)
 * 的候选权限回归。修正前该分支用 {@code SELECT id FROM projects ORDER BY
 * updated_at DESC,id LIMIT 4} 选出<b>全库</b>最近更新的四个项目——跨组织项目
 * 会占满候选名额并让当前组织用户的检索整体失败,同组织无项目角色的项目也会
 * 占名额。修正后候选在 SQL 内按「当前组织 + 项目可见权限」生成,再排序、再限四。
 *
 * 用真实 PostgreSQL(隔离库,{@code @ActiveProfiles("test")})。检索宿主与来源
 * 校验在此仅作为<b>可观测的回声桩</b>:本类的判定对象是候选生成,不是 Python
 * 检索本身;Python 检索与 Java 最终来源校验的既有覆盖见
 * {@code SharedRetrievalIntegrationTest} 与 {@code RetrievalAuthorizationIntegrationTest}。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProjectContentDiscoverCandidateScopeIntegrationTest {

    @Autowired IdentityRepository identities;
    @Autowired ProjectService projects;
    @Autowired ProjectRepository projectRepository;
    @Autowired AuthorizationGuard guard;
    @Autowired RetrievalStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired NamedParameterJdbcTemplate namedJdbc;

    private ProjectContentDiscoverCapability capability;
    private SharedRetrievalHost retrievalHost;

    @BeforeEach
    void setUp() {
        retrievalHost = Mockito.mock(SharedRetrievalHost.class);
        GaRetrievalAccess access = Mockito.mock(GaRetrievalAccess.class);
        Mockito.when(access.host()).thenReturn(retrievalHost);
        RetrievalStore storeMock = Mockito.mock(RetrievalStore.class);
        Mockito.when(storeMock.validateSources(Mockito.any(Validation.class))).thenAnswer(invocation -> {
            Validation request = invocation.getArgument(0);
            return new ValidationResult(request.protocolVersion(), request.requestId(), request.workload(),
                    request.scopeGrant(), request.profileId(), request.indexGeneration(), request.deadline(),
                    request.sources().stream().map(Source::entryId).toList(), List.of());
        });
        // 回声桩:Host 实际上只对"已被授权并被选中的项目"发起检索;桩把被检索的
        // projectId 回显成一条来源,使"究竟检索了哪些项目"在结果里可观测。
        Mockito.when(retrievalHost.search(Mockito.any(RetrievalQuery.class), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(invocation -> echo(invocation.getArgument(0, RetrievalQuery.class)));
        capability = new ProjectContentDiscoverCapability(access, storeMock, namedJdbc, guard, projectRepository);
    }

    // --------------------------------------------------------------- 任务 A 验收

    @Test
    void implicitDiscoveryCandidatesAreScopedToSelectedOrganizationAndProjectRole() {
        UUID orgA = org();
        UUID orgB = org();
        ActorContext caller = actor(orgA);
        ActorContext otherOwnerInA = actor(orgA);
        ActorContext foreignOwner = actor(orgB);

        Project authorizedOld = project(caller);
        Project noRoleSameOrg = project(otherOwnerInA);
        Project crossOrgNewest = project(foreignOwner);

        // updated_at:跨组织最新 > 同组织无角色 > 有权限的旧项目
        setUpdatedAt(crossOrgNewest, Instant.now());
        setUpdatedAt(noRoleSameOrg, Instant.now().minusSeconds(60));
        setUpdatedAt(authorizedOld, Instant.now().minusSeconds(120));

        // 三个项目都建了可用索引:缺少修复时它们会一起占满候选名额并被检索。
        seedReadyIndex(crossOrgNewest.id());
        seedReadyIndex(noRoleSameOrg.id());
        seedReadyIndex(authorizedOld.id());

        CapabilityResult result = invokeAs(caller, UUID.randomUUID());

        assertThat(result.status()).isEqualTo(CapabilityResult.Status.SUCCEEDED);
        List<Map<String, Object>> candidates = candidates(result);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().get("projectId")).isEqualTo(authorizedOld.id().toString());
        assertThat(candidates.getFirst().get("title")).isEqualTo(authorizedOld.title());
        // 无权限项目(跨组织 / 同组织无角色)的标题、数量与正文都不出现。
        assertThat(result.content().toString())
                .doesNotContain(crossOrgNewest.title(), noRoleSameOrg.title());
        assertThat(result.content().get("searchedProjects")).isEqualTo(1);
    }

    @Test
    void implicitDiscoveryWithoutAnyVisibleAuthorizedProjectReturnsNoCandidates() {
        UUID orgA = org();
        ActorContext caller = actor(orgA);
        // 同组织但调用者没有项目的项目,被索引且最近更新——修正前会占满候选。
        Project noRoleSameOrg = project(actor(orgA));
        seedReadyIndex(noRoleSameOrg.id());
        // 跨组织项目同样被索引。
        Project crossOrg = project(actor(org()));
        seedReadyIndex(crossOrg.id());

        CapabilityResult result = invokeAs(caller, UUID.randomUUID());

        // 没有任何可见项目:候选为空、不检索任何项目,也绝不返回标题/数量/正文。
        assertThat(result.status()).isEqualTo(CapabilityResult.Status.SUCCEEDED);
        assertThat(candidates(result)).isEmpty();
        assertThat(result.content().get("searchedProjects")).isEqualTo(0);
        assertThat(result.content().toString()).doesNotContain(noRoleSameOrg.title(), crossOrg.title());
    }

    @Test
    void visibleRecentQueryFiltersThenOrdersByUpdatedAtDescThenLimitsFour() {
        UUID orgA = org();
        ActorContext caller = actor(orgA);
        List<Project> authorized = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Project p = project(caller);
            setUpdatedAt(p, Instant.now().minusSeconds(600 - i * 60L));
            authorized.add(p);
        }
        // 更晚更新但调用者无角色的同组织项目,以及更晚更新的跨组织项目。
        Project hiddenNoRole = project(actor(orgA));
        setUpdatedAt(hiddenNoRole, Instant.now());
        Project crossOrg = project(actor(org()));
        setUpdatedAt(crossOrg, Instant.now());

        List<Project> visible = projectRepository.findVisibleRecentInOrg(orgA, caller.userId(), false, 4);

        assertThat(visible).extracting(Project::id).containsExactly(
                authorized.get(4).id(), authorized.get(3).id(), authorized.get(2).id(), authorized.get(1).id());
        assertThat(visible).extracting(Project::id).doesNotContain(hiddenNoRole.id(), crossOrg.id());
    }

    // ------------------------------------------------------------------- 夹具

    private CapabilityResult invokeAs(ActorContext actor, UUID run) {
        try (var scope = ActorContextHolder.bind(actor)) {
            Map<String, Object> arguments = Map.of("query", "EXP-2026-A");
            var commit = capability.prepare(arguments, () -> true, run);
            CapabilityInvocation invocation = new CapabilityInvocation(
                    UUID.randomUUID(), "src-scope:" + run, "project.content.discover", null, run, arguments);
            return commit.apply(invocation);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> candidates(CapabilityResult result) {
        return (List<Map<String, Object>>) result.content().get("candidates");
    }

    private SearchResult echo(RetrievalQuery query) {
        UUID project = query.projectId();
        String content = "observed content for " + project;
        Source source = new Source(UUID.randomUUID(), project, project, null, "node:" + project, "v1",
                Hashes.sha256Hex(content), "NODE", "PROJECT", "CONFIRMED", 1, content,
                new Location(0, 0, content.length()));
        Instant now = Instant.now();
        return new SearchResult("retrieval.v1", UUID.randomUUID(), new Workload("GA_RUN", UUID.randomUUID(), 1),
                new Grant(UUID.randomUUID(), 1, now.plusSeconds(120)), RetrievalWire.PROFILE, UUID.randomUUID(),
                now.plusSeconds(60), "python-rag.v1", "OK", false,
                List.of(new Ranked(source, List.of("project-lexical"), 1.0d)), List.of());
    }

    private void seedReadyIndex(UUID project) {
        UUID generation = store.ensureProjectGeneration(project);
        String content = "seeded evidence for " + project;
        jdbc.update("""
                INSERT INTO retrieval_entries(id, corpus_id, project_id, source_kind, source_id, source_ref,
                    scope, authority, content, content_hash, metadata, embedding_status, profile_id, index_generation)
                VALUES (?,?,?,'NODE',?,?,'PROJECT','CONFIRMED',?,?,CAST('{}' AS jsonb),'READY',?,?)
                """, UUID.randomUUID(), project, project, UUID.randomUUID(), "node:" + project, content,
                Hashes.sha256Hex(content), RetrievalWire.PROFILE, generation);
    }

    private void setUpdatedAt(Project project, Instant instant) {
        jdbc.update("UPDATE projects SET updated_at=? WHERE id=?", Timestamp.from(instant), project.id());
    }

    private UUID org() {
        UUID id = UUID.randomUUID();
        identities.insertOrganizationIfAbsent(id, "src-scope-org", "STANDARD");
        return id;
    }

    private ActorContext actor(UUID org) {
        UUID user = UUID.randomUUID();
        identities.insertUserIfAbsent(user, "src-scope-user");
        identities.upsertMembership(org, user, OrgRole.MEMBER);
        return ActorContext.oidc(user, org);
    }

    private Project project(ActorContext actor) {
        try (var scope = ActorContextHolder.bind(actor)) {
            return projects.createProject("src-scope-" + UUID.randomUUID());
        }
    }
}

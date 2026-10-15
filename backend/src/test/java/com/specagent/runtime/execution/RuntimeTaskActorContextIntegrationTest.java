package com.specagent.runtime.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.specagent.access.AccessDeniedException;
import com.specagent.access.AccessResourceRepository;
import com.specagent.access.ActorContext;
import com.specagent.access.ActorContextHolder;
import com.specagent.access.ActorSource;
import com.specagent.access.AuthorizationGuard;
import com.specagent.access.Capability;
import com.specagent.access.LocalPrincipalIds;
import com.specagent.access.OrgRole;
import com.specagent.access.identity.IdentityRepository;
import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 文件名:RuntimeTaskActorContextIntegrationTest.java
 *
 * 测试目标:P2-D 的第一刀——{@code runtime_tasks} 持久化执行主体
 * (user/org/来源),后台执行时从<b>持久化事实</b>重建 {@link ActorContext}。
 *
 * <p>这条路径存在的理由:Recovery Worker / 内部 RPC 没有请求线程。修复前,
 * OIDC 模式下 GA 执行期的会话私有性判定({@code requireThreadOwnership})
 * 会因"线程无主体"一律拒绝;修复后主体从任务行重建并只在本次执行期间绑定,
 * 因此后台工作以真实发起者的身份展开,而不是匿名、本地管理员或服务超级权限。
 *
 * <p>用真实 PostgreSQL;判定用显式主体 API(后台任务的调用形态)。
 */
@SpringBootTest
@ActiveProfiles("test")
class RuntimeTaskActorContextIntegrationTest {

    @Autowired RuntimeTaskRepository tasks;
    @Autowired IdentityRepository identities;
    @Autowired AccessResourceRepository resources;
    @Autowired AuthorizationGuard guard;
    @Autowired ProjectService projectService;
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired NamedParameterJdbcTemplate jdbc;

    private final List<UUID> createdRuns = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID runId : createdRuns) {
            jdbc.update("DELETE FROM runtime_task_scopes WHERE task_id IN "
                    + "(SELECT id FROM runtime_tasks WHERE workspace_run_id = :r)",
                    Map.of("r", runId));
            jdbc.update("DELETE FROM runtime_tasks WHERE workspace_run_id = :r",
                    Map.of("r", runId));
            jdbc.update("DELETE FROM agent_run_events WHERE run_id = :r", Map.of("r", runId));
            jdbc.update("DELETE FROM agent_runs WHERE id = :r", Map.of("r", runId));
        }
        createdRuns.clear();
    }

    private UUID standardOrg(String name) {
        UUID id = UUID.randomUUID();
        identities.insertOrganizationIfAbsent(id, name, "STANDARD");
        return id;
    }

    private UUID user(String name) {
        UUID id = UUID.randomUUID();
        identities.insertUserIfAbsent(id, name);
        return id;
    }

    private Project createProjectAs(ActorContext actor, String title) {
        try (ActorContextHolder.Scope ignored = ActorContextHolder.bind(actor)) {
            return projectService.createProject(title + " " + UUID.randomUUID());
        }
    }

    /** 直接插入一个画布 Run(等价于入队前的业务事实,不经过完整命令链)。 */
    private UUID insertWorkspaceRun(UUID projectId) {
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_runs (id, project_id, trigger_type, status, trace,
                    created_at, owner_epoch)
                VALUES (:id, :projectId, 'answer_cycle', 'created', NULL, now(), 0)
                """, Map.of("id", runId, "projectId", projectId));
        createdRuns.add(runId);
        return runId;
    }

    // ------------------------------------------------ 主体三元组持久化与重建

    @Test
    void persistedOidcActorRoundTripsAndRebuildsTheTrustedSubject() {
        UUID org = standardOrg("rt-roundtrip");
        UUID owner = user("rt-roundtrip-owner");
        identities.upsertMembership(org, owner, OrgRole.MEMBER);
        Project project = createProjectAs(ActorContext.oidc(owner, org), "主体项目");
        UUID runId = insertWorkspaceRun(project.id());

        UUID taskId = UUID.randomUUID();
        TaskActor persisted = new TaskActor(owner, org, ActorSource.OIDC);
        tasks.insertIfAbsent(taskId, RuntimeTaskFamily.WORKSPACE, runId, null,
                "answer_cycle", 3, RuntimeExecutionProtocol.VERSION, persisted);

        RuntimeTask stored = tasks.findById(taskId).orElseThrow();
        assertThat(stored.actor())
                .as("主体三元组必须原样落库并可读回")
                .isEqualTo(persisted);

        ActorContext rebuilt = stored.actor().toActorContext();
        assertThat(rebuilt).isEqualTo(ActorContext.oidc(owner, org));
        assertThat(rebuilt.source()).isEqualTo(ActorSource.OIDC);

        // 重建主体就是项目授权的判定主体:创建者持 MANAGER,因而可读。
        assertThat(guard.projectDecisionFor(rebuilt, project.id(), Capability.PROJECT_READ).allowed())
                .isTrue();
        assertThat(guard.projectDecisionFor(rebuilt, project.id(), Capability.PROJECT_MANAGE).allowed())
                .isTrue();
    }

    @Test
    void legacyRowWithoutActorColumnsDefaultsToTheExplicitLocalPrincipal() {
        // 第一阶段遗留的直插路径(不含主体列):数据库默认值必须把它收敛到
        // **显式的本地主体**,而不是留下无归属的任务(那会变成隐形超级用户)。
        Project project = createProjectAs(
                ActorContext.local(LocalPrincipalIds.USER_ID, LocalPrincipalIds.ORG_ID), "遗留任务项目");
        UUID runId = insertWorkspaceRun(project.id());

        UUID taskId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO runtime_tasks (actor_user_id, actor_org_id, actor_source, id, family, workspace_run_id, ga_run_id, task_type,
                    state, attempt_count, max_attempts, available_at, created_at, protocol_version)
                VALUES ('00000000-0000-4000-8000-000000000002'::uuid, '00000000-0000-4000-8000-000000000001'::uuid, 'LOCAL', :id, 'WORKSPACE', :runId, NULL, 'answer_cycle',
                    'QUEUED', 0, 3, now(), now(), 1)
                """, Map.of("id", taskId, "runId", runId));

        TaskActor actor = tasks.findById(taskId).orElseThrow().actor();
        assertThat(actor.source()).isEqualTo(ActorSource.LOCAL);
        assertThat(actor.userId()).isEqualTo(LocalPrincipalIds.USER_ID);
        assertThat(actor.orgId()).isEqualTo(LocalPrincipalIds.ORG_ID);
    }

    @Test
    void unknownActorSourceIsRejectedInsteadOfDefaulting() {
        assertThat(catchThrowableOfType(
                () -> ActorSource.fromCode("SUPERUSER"),
                IllegalArgumentException.class))
                .as("未知来源必须失败关闭,绝不静默回落到某个默认权限")
                .isNotNull();
        assertThat(catchThrowableOfType(
                () -> ActorSource.fromCode(null),
                IllegalArgumentException.class)).isNotNull();
    }

    // -------------------------------------------------- 重建主体驱动会话私有性

    /**
     * GA 的会话正文写入经 {@code requireThreadOwnership} 收口。后台执行线程
     * 没有请求主体,因此必须能用<b>任务行重建出来的主体</b>通过这条判定;
     * 而他人(哪怕同组织管理员)仍然被拒且表现为"会话不存在"。
     */
    @Test
    void rebuiltActorDrivesThreadOwnershipForBackgroundWork() {
        UUID org = standardOrg("rt-thread");
        UUID owner = user("rt-thread-owner");
        UUID other = user("rt-thread-other");
        identities.upsertMembership(org, owner, OrgRole.MEMBER);
        identities.upsertMembership(org, other, OrgRole.ADMIN);

        UUID threadId;
        try (ActorContextHolder.Scope ignored = ActorContextHolder.bind(ActorContext.oidc(owner, org))) {
            threadId = conversations.createThread().id();
        }
        assertThat(resources.threadFacts(threadId).orElseThrow().ownerUserId()).isEqualTo(owner);

        ActorContext rebuiltOwner = new TaskActor(owner, org, ActorSource.OIDC).toActorContext();
        assertThatCode(() -> guard.requireThreadOwnershipFor(rebuiltOwner, threadId))
                .as("重建出来的发起者主体必须能驱动后台会话写入")
                .doesNotThrowAnyException();

        AccessDeniedException denied = catchThrowableOfType(
                () -> guard.requireThreadOwnershipFor(
                        new TaskActor(other, org, ActorSource.OIDC).toActorContext(), threadId),
                AccessDeniedException.class);
        assertThat(denied).isNotNull();
        assertThat(denied.concealed())
                .as("他人的私有会话对非属主一律不可见")
                .isTrue();
    }

    // -------------------------------------------------------------- 系统主体

    @Test
    void systemActorCarriesNoUserAndIsNeverAUserSubject() {
        UUID org = standardOrg("rt-system");
        ActorContext system = new TaskActor(null, org, ActorSource.SYSTEM).toActorContext();
        assertThat(system.source()).isEqualTo(ActorSource.SYSTEM);
        assertThat(system.isUser())
                .as("系统主体无 user 字段,不得被当作用户或超级管理员")
                .isFalse();
        assertThat(system.userId()).isNull();
    }
}
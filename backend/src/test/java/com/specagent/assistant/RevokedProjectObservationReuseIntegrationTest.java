package com.specagent.assistant;

import com.specagent.access.AccessDeniedException;
import com.specagent.access.AccessResourceRepository;
import com.specagent.access.ActorContext;
import com.specagent.access.ActorContextHolder;
import com.specagent.access.AuthorizationGuard;
import com.specagent.access.Capability;
import com.specagent.access.OrgRole;
import com.specagent.access.identity.IdentityRepository;
import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.assistant.conversation.GlobalAssistantMessage;
import com.specagent.assistant.conversation.GlobalAssistantRun;
import com.specagent.assistant.conversation.GlobalAssistantThread;
import com.specagent.assistant.conversation.GlobalAssistantThreadListItem;
import com.specagent.assistant.runtime.GaCheckpointStore;
import com.specagent.assistant.runtime.GaExecutionPreparation;
import com.specagent.assistant.runtime.GlobalAssistantApplicationService;
import com.specagent.assistant.runtime.GlobalAssistantRunEventService;
import com.specagent.assistant.runtime.GlobalAssistantStreamService;
import com.specagent.modelsettings.OpenCodeSettings;
import com.specagent.modelsettings.OpenCodeSettingsRepository;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 文件名:RevokedProjectObservationReuseIntegrationTest.java
 *
 * 用途:F1 修复的验收测试(<b>断言漏洞已被阻断</b>,不是复现漏洞)。
 *
 * <p>场景:用户先拥有项目权限并让 GA 保存了该项目的宿主工具观察,随后项目角色被撤销;
 * 用户仍属于组织、仍拥有 GA 会话,但不能再通过旧会话读取、展示、恢复或向模型发送
 * 该项目的旧内容。撤权前与对照会话必须照常工作,其他组织/其他用户不得因此获得任何
 * 额外权限。
 *
 * <p>依赖账本来自既有事实({@code global_assistant_run_events} 的
 * {@code TOOL_COMPLETED.payload.resourceRefs} 与 {@code UI_ACTION.payload.resourceId}),
 * 判定收敛在 {@code GaSourceDependencyGuard},读取侧统一走同一套 SQL 谓词。
 * 全程使用 OIDC 主体:LOCAL 主体在 {@code projectDecisionFor} 里不查项目角色,
 * 用它测不出真实隔离。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RevokedProjectObservationReuseIntegrationTest {

    @Autowired IdentityRepository identities;
    @Autowired ProjectService projects;
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired com.specagent.assistant.conversation.GaSourceDependencyGuard sourceDependencies;
    @Autowired GlobalAssistantApplicationService application;
    @Autowired GlobalAssistantStreamService streams;
    @Autowired AuthorizationGuard guard;
    @Autowired AccessResourceRepository resources;
    @Autowired GlobalAssistantRunEventService events;
    @Autowired GaExecutionPreparation preparation;
    @Autowired GaCheckpointStore checkpoints;
    @Autowired com.specagent.assistant.api.GaCapabilityBroker broker;
    @Autowired com.specagent.assistant.tool.GaCatalogProjection catalogProjection;
    @Autowired com.specagent.assistant.runtime.GaExecutionStore executions;
    @Autowired JdbcTemplate jdbc;
    @MockBean OpenCodeSettingsRepository settings;

    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @BeforeEach
    void fixture() {
        Instant revision = Instant.now();
        when(settings.find()).thenReturn(Optional.of(
                new OpenCodeSettings("test-key-no-network", "work", "test-model", revision, revision)));
    }

    // ------------------------------------------------------------------ 场景 1-6

    @Test
    void revokedProjectRoleBlocksEveryReadOfTheDependentConversationButKeepsTheControlConversation() throws Exception {
        UUID orgA = org();
        UUID orgB = org();
        ActorContext owner = actor(orgA);
        ActorContext otherMember = actor(orgA);
        ActorContext foreign = actor(orgB);

        Project project = project(owner);
        Project otherProject = project(otherMember);

        UUID dependentThread;
        UUID controlThread;
        UUID foreignThread;
        try (var scope = ActorContextHolder.bind(owner)) {
            dependentThread = conversations.createThread().id();
            controlThread = conversations.createThread().id();
        }
        try (var scope = ActorContextHolder.bind(foreign)) {
            foreignThread = conversations.createThread().id();
        }

        String projectEvidence = "项目 " + project.title() + " 的已确认观察：EXP-2026-A 证据片段。";
        String controlBody = "对照会话内容：只讨论 " + otherProject.title() + "。";

        // 撤权前:项目读取与会话读取都正常(否则后面的"被阻断"没有意义)。
        assertThat(guard.projectDecisionFor(owner, project.id(), Capability.PROJECT_READ).allowed()).isTrue();
        try (var scope = ActorContextHolder.bind(owner)) {
            assertThatCode(() -> conversations.findThreadOwned(dependentThread)).doesNotThrowAnyException();
            assertThatCode(() -> application.listMessages(dependentThread)).doesNotThrowAnyException();
        }

        // 依赖会话的观察轮:真实的宿主工具观察(TOOL_COMPLETED 的 resourceRefs 指向该项目)
        // + 引用该观察的助手正文 + 一条助手可见的来源事件。
        GlobalAssistantRun observationRun;
        try (var scope = ActorContextHolder.bind(owner)) {
            observationRun = conversations.createRunWithUserMessage(dependentThread,
                    "请查询项目 " + project.title() + " 的实际内容并引用来源。", "ga-test", "ga-test", "ga-test");
            events.append(observationRun.id(), "TOOL_COMPLETED", new LinkedHashMap<>(Map.of(
                    "capabilityId", "project.content.discover", "toolCallId", "obs-1",
                    "resourceRefs", List.of("project:" + project.id()), "resultCount", 1)));
            events.append(observationRun.id(), "ASSISTANT_DELTA", new LinkedHashMap<>(Map.of("text", projectEvidence)));
            conversations.appendAssistantMessage(dependentThread, projectEvidence, observationRun.id());
        }
        jdbc.update("UPDATE global_assistant_runs SET status='COMPLETED' WHERE id=?", observationRun.id());

        try (var scope = ActorContextHolder.bind(owner)) {
            conversations.appendUserMessage(controlThread, controlBody, null);
        }
        try (var scope = ActorContextHolder.bind(foreign)) {
            conversations.appendUserMessage(foreignThread, "外部组织的自有会话", null);
        }

        // 撤权:删除 owner 在该项目上的项目角色。
        assertThat(jdbc.update("DELETE FROM project_members WHERE project_id=? AND user_id=?",
                project.id(), owner.userId())).isEqualTo(1);

        // 项目读取被拒,但组织成员资格与 GA 会话属主资格仍然有效(隔离不是"用户被禁用")。
        assertThat(guard.projectDecisionFor(owner, project.id(), Capability.PROJECT_READ).allowed()).isFalse();
        assertThat(resources.findMembership(orgA, owner.userId())).hasValueSatisfying(m -> assertThat(m.active()).isTrue());
        assertThatCode(() -> guard.requireThreadOwnershipFor(owner, dependentThread)).doesNotThrowAnyException();

        // 场景 1:受影响会话的每一处读取都被拒绝(消息列表、详情/摘要、活动)。
        try (var scope = ActorContextHolder.bind(owner)) {
            assertThatThrownBy(() -> application.listMessages(dependentThread))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> application.requireThreadReadable(dependentThread))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> application.threadActivity(dependentThread))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> conversations.requireSourceDependenciesReadable(dependentThread))
                    .isInstanceOf(AccessDeniedException.class);
        }

        // 事件读取(JSON 回放)同样被拒:载荷携带项目资源引用与来源卡片正文。
        try (var scope = ActorContextHolder.bind(owner)) {
            assertThatThrownBy(() -> streams.listEnvelopes(observationRun.id()))
                    .isInstanceOf(AccessDeniedException.class);
        }

        // 场景 2:会话列表不含受影响会话(标题/预览不泄露),且它不占用分页名额。
        List<GlobalAssistantThreadListItem> ownerList;
        try (var scope = ActorContextHolder.bind(owner)) {
            ownerList = application.listThreads();
        }
        assertThat(ownerList).extracting(GlobalAssistantThreadListItem::threadId)
                .doesNotContain(dependentThread).contains(controlThread);
        assertThat(ownerList.toString()).doesNotContain(projectEvidence);

        // 场景 3:checkpoint GET / LIST / PUT 全部被拒(读取、恢复、写入同一收口)。
        UUID checkpointRun;
        try (var scope = ActorContextHolder.bind(owner)) {
            checkpointRun = conversations.createRunWithUserMessage(dependentThread,
                    "检查点轮", "ga-test", "ga-test", "ga-test").id();
        }
        UUID lease = UUID.randomUUID();
        jdbc.update("UPDATE global_assistant_runs SET status='RUNNING' WHERE id=?", checkpointRun);
        jdbc.update("""
                INSERT INTO ga_executions(run_id,thread_id,model_binding_id,provider,model,settings_revision,
                    execution_epoch,lease_id,deadline)
                VALUES(?,?,?,'OPENCODE_ZEN','fake','fixture',1,?,now()+interval '3 minutes')
                """, checkpointRun, dependentThread, UUID.randomUUID(), lease);
        jdbc.update("INSERT INTO ga_checkpoint_heads(thread_id,version) VALUES(?,0) ON CONFLICT DO NOTHING", dependentThread);
        String checkpointJson = mapper.writeValueAsString(Map.of("tag", "dict", "value", Map.of(
                "id", "cp-obs-1", "messages", List.of(Map.of("role", "tool", "content", projectEvidence)))));
        jdbc.update("""
                INSERT INTO ga_checkpoints(thread_id,checkpoint_id,parent_checkpoint_id,checkpoint,metadata,new_versions)
                VALUES(?,?,NULL,?,?,?) ON CONFLICT (thread_id,checkpoint_id) DO NOTHING
                """, dependentThread, "cp-obs-1", checkpointJson, "{}", "{}");
        Map<String, Object> checkpointBase = new LinkedHashMap<>();
        checkpointBase.put("protocolVersion", "ga-checkpoint.v1");
        checkpointBase.put("runId", checkpointRun.toString());
        checkpointBase.put("executionEpoch", 1);
        checkpointBase.put("leaseId", lease.toString());
        checkpointBase.put("threadId", dependentThread.toString());
        checkpointBase.put("namespace", "ga:langchain-ga.v1");
        checkpointBase.put("expectedVersion", 0);
        Map<String, Object> getRequest = new LinkedHashMap<>(checkpointBase);
        getRequest.put("checkpointId", "cp-obs-1");
        Map<String, Object> listRequest = new LinkedHashMap<>(checkpointBase);
        listRequest.put("before", null);
        listRequest.put("limit", 10);
        listRequest.put("filter", Map.of());
        Map<String, Object> putRequest = new LinkedHashMap<>(checkpointBase);
        putRequest.put("checkpointId", "cp-obs-2");
        putRequest.put("parentCheckpointId", null);
        putRequest.put("checkpoint", "{}");
        putRequest.put("metadata", "{}");
        putRequest.put("newVersions", "{}");
        for (var request : List.of(getRequest, listRequest, putRequest)) {
            String body = mapper.writeValueAsString(request);
            assertThatThrownBy(() -> checkpoints.apply(request == getRequest ? "GET"
                    : request == listRequest ? "LIST" : "PUT", body))
                    .as("checkpoint operation must be refused after revocation")
                    .isInstanceOf(AccessDeniedException.class);
        }
        // 每线程至多一个活跃 Run(V28 uq_ga_runs_single_active):收口后建下一轮。
        jdbc.update("UPDATE global_assistant_runs SET status='FAILED' WHERE id=?", checkpointRun);

        // 场景 4:下一轮 prepare 不把旧观察装入执行信封,并以控制面终态化失败关闭。
        UUID nextRun;
        try (var scope = ActorContextHolder.bind(owner)) {
            nextRun = conversations.createRunWithUserMessage(dependentThread,
                    "继续：把上一步的观察整理一下。", "ga-test", "ga-test", "ga-test").id();
        }
        GaExecutionPreparation.Prepared prepared;
        try (var scope = ActorContextHolder.bind(owner)) {
            prepared = preparation.prepare(dependentThread, nextRun, "继续：把上一步的观察整理一下。", null);
        }
        assertThat(prepared).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM global_assistant_runs WHERE id=?", String.class, nextRun))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_code FROM global_assistant_runs WHERE id=?", String.class, nextRun))
                .isEqualTo("GA_SOURCE_REVOKED");
        // 失败关闭不写任何会话正文:历史一个字节都没有进入执行信封。
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM global_assistant_messages WHERE thread_id=? AND run_id=? AND role='ASSISTANT'
                """, Integer.class, dependentThread, nextRun)).isZero();
        // 判定发生在 GaExecutionPreparation 构造执行信封之前:该轮没有任何执行绑定
        // (信封只会在 initialize 之后写入 ga_executions.execution_request),
        // 因此不可能有信封被发送到 Python(跨语言联调的可证伪点)。
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ga_executions WHERE run_id=?", Integer.class, nextRun)).isZero();

        // 场景 5:对照会话完全没有被误伤(可读、预览正常、不含受影响项目的内容)。
        try (var scope = ActorContextHolder.bind(owner)) {
            List<GlobalAssistantMessage> controlMessages = application.listMessages(controlThread);
            assertThat(controlMessages).anySatisfy(m -> assertThat(m.content()).contains(controlBody));
            assertThat(controlMessages).noneSatisfy(m -> assertThat(m.content()).contains(projectEvidence));
            assertThatCode(() -> application.requireThreadReadable(controlThread)).doesNotThrowAnyException();
        }
        assertThat(ownerList).anySatisfy(item -> {
            assertThat(item.threadId()).isEqualTo(controlThread);
            assertThat(item.preview()).doesNotContain(project.title());
        });

        // 场景 6:另一组织/另一用户不因这个修复获得任何额外权限。
        assertThat(guard.projectDecisionFor(foreign, project.id(), Capability.PROJECT_READ).allowed()).isFalse();
        assertThatThrownBy(() -> guard.requireThreadOwnershipFor(foreign, dependentThread))
                .isInstanceOf(AccessDeniedException.class);
        try (var scope = ActorContextHolder.bind(foreign)) {
            assertThatThrownBy(() -> application.listMessages(dependentThread))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatCode(() -> application.listMessages(foreignThread)).doesNotThrowAnyException();
        }
        try (var scope = ActorContextHolder.bind(otherMember)) {
            assertThatThrownBy(() -> application.listMessages(dependentThread))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    // -------------------------------------------------------------------- 场景 8

    @Test
    void unknownOrDamagedDependenciesFailClosedWhileDefinitelyIndependentConversationsStayReadable() {
        UUID org = org();
        ActorContext owner = actor(org);
        Project project = project(owner);

        // 依赖未知/损坏的四种形态:全部必须隔离。
        UUID missingLedgerKey = observedThread(owner, Map.of(
                "capabilityId", "project.search", "toolCallId", "t1"));
        UUID nonArrayLedger = observedThread(owner, Map.of(
                "capabilityId", "project.search", "toolCallId", "t2", "resourceRefs", "project:" + project.id()));
        UUID unclassifiedCapability = observedThread(owner, Map.of(
                "capabilityId", "some.future.tool", "toolCallId", "t3"));
        UUID withoutCapabilityId = observedThread(owner, Map.of("toolCallId", "t4"));

        // 确知无项目依赖的形态:不得误伤。
        UUID clarification = observedThread(owner, Map.of(
                "capabilityId", "user-input.request", "toolCallId", "u1"));
        UUID navigateWithoutProject = observedThread(owner, Map.of(
                "capabilityId", "ui.navigate", "toolCallId", "n1", "destination", "PROJECTS"));
        UUID emptyProjectRefs = observedThread(owner, Map.of(
                "capabilityId", "project.search", "toolCallId", "e1", "resourceRefs", List.of()));
        UUID webOnly = observedThread(owner, Map.of(
                "capabilityId", "web.search", "toolCallId", "w1", "resourceRefs", List.of()));

        assertThat(isolated(owner, missingLedgerKey)).isTrue();
        assertThat(isolated(owner, nonArrayLedger)).isTrue();
        assertThat(isolated(owner, unclassifiedCapability)).isTrue();
        assertThat(isolated(owner, withoutCapabilityId)).isTrue();

        assertThat(isolated(owner, clarification)).isFalse();
        assertThat(isolated(owner, navigateWithoutProject)).isFalse();
        assertThat(isolated(owner, emptyProjectRefs)).isFalse();
        assertThat(isolated(owner, webOnly)).isFalse();

        // 确知无依赖的会话即使列表里也照常出现,不被"失败关闭"误伤。
        List<GlobalAssistantThreadListItem> list;
        try (var scope = ActorContextHolder.bind(owner)) {
            list = application.listThreads();
        }
        assertThat(list).extracting(GlobalAssistantThreadListItem::threadId)
                .contains(clarification, navigateWithoutProject)
                .doesNotContain(missingLedgerKey, unclassifiedCapability);
    }

    @Test
    void navigationToARevokedProjectAlsoBlocksTheConversation() {
        UUID org = org();
        ActorContext owner = actor(org);
        Project project = project(owner);
        UUID thread;
        try (var scope = ActorContextHolder.bind(owner)) {
            thread = conversations.createThread().id();
        }
        UUID run;
        try (var scope = ActorContextHolder.bind(owner)) {
            run = conversations.createRunWithUserMessage(thread, "打开这个项目", "ga-test", "ga-test", "ga-test").id();
            // ui.navigate 绕过 capability invocation,项目依赖只在 UI_ACTION 事件里。
            events.append(run, "UI_ACTION", Map.of("destination", "PROJECT", "resourceId", project.id().toString()));
        }
        jdbc.update("UPDATE global_assistant_runs SET status='COMPLETED' WHERE id=?", run);
        assertThat(isolated(owner, thread)).isFalse();
        jdbc.update("DELETE FROM project_members WHERE project_id=? AND user_id=?", project.id(), owner.userId());
        assertThat(isolated(owner, thread)).isTrue();
    }

    // ------------------------------------------------------------------- 场景 10

    /**
     * 并发撤权与历史读取:<b>不得违反冻结的锁顺序</b>。
     *
     * <p>本方法刻意脱离测试事务(真实提交的撤权),否则并发读者看不到撤权。断言两点:
     * <ul>
     *   <li>读取在撤权提交前可读、提交后一致被拒(快照语义,不存在"撤权后还读得到"的窗口);</li>
     *   <li>全程没有出现锁等待/死锁/其它异常——判定是纯读,不取项目行锁,也不取
     *       authorization scope 锁,因此不可能与撤权事务形成锁环。</li>
     * </ul>
     */
    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentRevocationAndHistoryReadNeverViolatesTheFrozenLockOrder() throws Exception {
        UUID org = org();
        ActorContext owner = actor(org);
        Project project = project(owner);
        UUID thread;
        UUID run;
        try (var scope = ActorContextHolder.bind(owner)) {
            thread = conversations.createThread().id();
            run = conversations.createRunWithUserMessage(thread, "并发观察轮", "ga-test", "ga-test", "ga-test").id();
            events.append(run, "TOOL_COMPLETED", new LinkedHashMap<>(Map.of(
                    "capabilityId", "project.content.discover", "toolCallId", "concurrent-1",
                    "resourceRefs", List.of("project:" + project.id()))));
        }
        jdbc.update("UPDATE global_assistant_runs SET status='COMPLETED' WHERE id=?", run);
        List<String> outcomes = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch firstRead = new java.util.concurrent.CountDownLatch(1);
        Thread reader = new Thread(() -> {
            for (int index = 0; index < 80; index++) {
                try (var scope = ActorContextHolder.bind(owner)) {
                    application.listMessages(thread);
                    outcomes.add("READABLE");
                    firstRead.countDown();
                } catch (AccessDeniedException denied) {
                    outcomes.add("BLOCKED");
                } catch (RuntimeException unexpected) {
                    outcomes.add("UNEXPECTED:" + unexpected.getClass().getSimpleName());
                }
            }
        }, "ga-source-dep-reader");
        try {
            reader.start();
            assertThat(firstRead.await(15, java.util.concurrent.TimeUnit.SECONDS))
                    .as("撤权前读取必须成功").isTrue();
            // 撤权在读取进行中提交(非事务,自动提交)。
            assertThat(jdbc.update("DELETE FROM project_members WHERE project_id=? AND user_id=?",
                    project.id(), owner.userId())).isEqualTo(1);
            // 等到读者观察到撤权后才收敛:撤权前可读、撤权后一致被拒。
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline && !outcomes.contains("BLOCKED")) Thread.sleep(10);
            reader.join(15000);
            assertThat(reader.isAlive()).isFalse();
            assertThat(outcomes).noneMatch(outcome -> outcome.startsWith("UNEXPECTED"));
            assertThat(outcomes).contains("READABLE").contains("BLOCKED");
            // 撤权提交之后:新的读取一致拒绝(不存在"提交后仍可读"的窗口)。
            try (var scope = ActorContextHolder.bind(owner)) {
                assertThatThrownBy(() -> application.listMessages(thread))
                        .isInstanceOf(AccessDeniedException.class);
            }
        } finally {
            reader.interrupt();
            jdbc.update("DELETE FROM ga_run_authorizations WHERE run_id=?", run);
            jdbc.update("DELETE FROM global_assistant_run_events WHERE run_id=?", run);
            jdbc.update("DELETE FROM global_assistant_messages WHERE thread_id=?", thread);
            jdbc.update("DELETE FROM global_assistant_runs WHERE thread_id=?", thread);
            jdbc.update("DELETE FROM global_assistant_threads WHERE id=?", thread);
            try { jdbc.update("DELETE FROM project_members WHERE project_id=?", project.id()); } catch (RuntimeException ignored) { }
            try { jdbc.update("UPDATE projects SET active_route_id=NULL WHERE id=?", project.id()); } catch (RuntimeException ignored) { }
            try { jdbc.update("DELETE FROM routes WHERE project_id=?", project.id()); } catch (RuntimeException ignored) { }
            try { jdbc.update("DELETE FROM projects WHERE id=?", project.id()); } catch (RuntimeException ignored) { }
            try { jdbc.update("DELETE FROM profiles WHERE org_id=?", org); } catch (RuntimeException ignored) { }
            try { jdbc.update("DELETE FROM organization_members WHERE org_id=?", org); } catch (RuntimeException ignored) { }
            try { jdbc.update("DELETE FROM organizations WHERE id=?", org); } catch (RuntimeException ignored) { }
            try { jdbc.update("DELETE FROM app_users WHERE id=?", owner.userId()); } catch (RuntimeException ignored) { }
        }
    }

    // ------------------------------------------------------- 场景 11:真实 broker 账本

    /**
     * <b>依赖账本必须取自真实 broker 产出的事件。</b>
     *
     * <p>上面各场景用 {@code List.of("project:" + id)} 构造观察事件,那是本设计初稿假设的
     * 字符串形态;但 {@code GaCapabilityBroker} 真正写进
     * {@code TOOL_COMPLETED.payload.resourceRefs} 的是
     * {@code GlobalAssistantToolPresentation.projectResources} 产出的<b>结构化对象</b>
     * ({@code {"kind":"PROJECT","id":...,"label":...,"metadata":...}}),而回给 Python 的
     * broker {@code sourceRefs} 才是 {@code project:<id>} 字符串。两者绝不能混为一谈。
     *
     * <p>本测试刻意走真实 {@code GaCapabilityBroker}(产品 bean,不是手写载荷)产生观察,
     * 然后断言撤权后该会话被隔离:它证明的是"账本格式与产品投影一致",而不只是
     * "某个手写字符串能被解析"。
     */
    @Test
    void realBrokerProjectObservationIsIsolatedOnceTheProjectRoleIsRevoked() throws Exception {
        UUID org = org();
        ActorContext owner = actor(org);
        Project project = project(owner);
        UUID thread;
        UUID run;
        try (var scope = ActorContextHolder.bind(owner)) {
            thread = conversations.createThread().id();
            run = conversations.createRunWithUserMessage(thread, "列出最近项目", "ga-test", "ga-test", "ga-test").id();
        }
        var catalog = catalogProjection.current();
        var execution = new com.specagent.assistant.runtime.GaExecutionStore.Scope(run, 1, UUID.randomUUID());
        executions.initialize(execution, new com.specagent.assistant.runtime.GaExecutionStore.Binding(thread,
                UUID.randomUUID(), "OPENCODE_ZEN", "test-model", "opencode-settings:" + Instant.now(),
                Instant.now().plusSeconds(120), catalog.tools()), catalog.descriptors());
        jdbc.update("UPDATE global_assistant_runs SET status='RUNNING' WHERE id=?", run);
        UUID modelCall = UUID.randomUUID();
        executions.reserve(execution, modelCall, "MODEL", com.specagent.common.Hashes.sha256Hex("broker-ledger-observation"));
        executions.complete(execution, modelCall, catalogProjection.canonical(
                new com.specagent.model.contract.GaModelContract.Response(
                        com.specagent.model.contract.GaModelContract.VERSION, "",
                        List.of(new com.specagent.model.contract.GaModelContract.ToolCall(
                                "broker-ledger", "project_list_recent", Map.of("limit", 10))),
                        "tool_calls", new com.specagent.model.contract.GaModelContract.Usage(1, 1))));
        Map<String, Object> arguments = Map.of("limit", 10);

        var response = broker.invoke(catalogProjection.canonical(Map.of(
                "protocolVersion", "ga-capability-invocation.v1", "runId", run.toString(),
                "executionEpoch", 1, "leaseId", execution.leaseId().toString(),
                "toolCallId", "broker-ledger", "capabilityId", "project.list_recent",
                "descriptorVersion", "1", "catalogHash", catalog.hash(), "arguments", arguments,
                "argumentsHash", com.specagent.common.Hashes.sha256Hex(catalogProjection.canonical(arguments)))));
        assertThat(response.status()).isEqualTo(200);
        var body = (com.fasterxml.jackson.databind.JsonNode) response.body();
        assertThat(body.path("status").asText()).isEqualTo("SUCCEEDED");

        // 真实事件载荷:PROJECT 资源必须是结构化对象,且携带本项目的 id。
        var refs = mapper.readTree(jdbc.queryForObject(
                "SELECT payload FROM global_assistant_run_events WHERE run_id=? AND type='TOOL_COMPLETED'",
                String.class, run)).path("resourceRefs");
        assertThat(refs.isArray()).isTrue();
        assertThat(refs).anySatisfy(ref -> {
            assertThat(ref.path("kind").asText()).isEqualTo("PROJECT");
            assertThat(ref.path("id").asText()).isEqualTo(project.id().toString());
        });

        // 授权时正常读取。
        assertThat(isolated(owner, thread)).isFalse();
        try (var scope = ActorContextHolder.bind(owner)) {
            assertThatCode(() -> application.listMessages(thread)).doesNotThrowAnyException();
        }

        // 撤权后:同一份真实事件必须让判定收敛为隔离。
        assertThat(jdbc.update("DELETE FROM project_members WHERE project_id=? AND user_id=?",
                project.id(), owner.userId())).isEqualTo(1);
        assertThat(isolated(owner, thread)).isTrue();
        try (var scope = ActorContextHolder.bind(owner)) {
            assertThatThrownBy(() -> application.listMessages(thread)).isInstanceOf(AccessDeniedException.class);
        }
    }

    // -------------------------------------------------------------------- 夹具

    private boolean isolated(ActorContext actor, UUID threadId) {
        return sourceDependencies.isThreadIsolated(actor, threadId);
    }

    /** 建线程 + 一轮 run + 一条 TOOL_COMPLETED 观察,返回线程 id。 */
    private UUID observedThread(ActorContext owner, Map<String, Object> toolCompletedPayload) {
        UUID thread;
        try (var scope = ActorContextHolder.bind(owner)) {
            thread = conversations.createThread().id();
        }
        UUID run;
        try (var scope = ActorContextHolder.bind(owner)) {
            run = conversations.createRunWithUserMessage(thread, "观察轮", "ga-test", "ga-test", "ga-test").id();
            events.append(run, "TOOL_COMPLETED", new LinkedHashMap<>(toolCompletedPayload));
        }
        jdbc.update("UPDATE global_assistant_runs SET status='COMPLETED' WHERE id=?", run);
        return thread;
    }

    private UUID org() {
        UUID id = UUID.randomUUID();
        identities.insertOrganizationIfAbsent(id, "src-dep-org", "STANDARD");
        return id;
    }

    private ActorContext actor(UUID org) {
        UUID user = UUID.randomUUID();
        identities.insertUserIfAbsent(user, "src-dep-user");
        identities.upsertMembership(org, user, OrgRole.MEMBER);
        return ActorContext.oidc(user, org);
    }

    private Project project(ActorContext actor) {
        try (var scope = ActorContextHolder.bind(actor)) {
            return projects.createProject("src-dep-" + UUID.randomUUID());
        }
    }
}

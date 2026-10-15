package com.specagent.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.specagent.access.identity.IdentityRepository;
import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.assistant.tool.GlobalProjectSearchService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 文件名:ResourceIsolationIntegrationTest.java
 *
 * 测试目标:P2-C 的资源隔离——项目与会话的归属判定在<b>真实 PostgreSQL</b>
 * 上的行为,以及三条核心不变量:
 *
 * <ol>
 *   <li><b>跨组织 = 不可见</b>(状态码与错误码都与"不存在"一致);</li>
 *   <li><b>同组织但无项目角色 = 也看不见</b>——列表与 GA 搜索必须在 SQL
 *       候选生成阶段同时按组织和项目访问权限过滤,标题、数量、预览与检索
 *       结果都不能泄露;</li>
 *   <li><b>legacy / local 组织不是授权豁免</b>——单机权限只属于受限本地
 *       身份适配器产生的 LOCAL 主体,OIDC 用户与禁用用户都拿不到。</li>
 * </ol>
 *
 * 判定用<b>显式主体</b>({@code projectDecisionFor})而不是线程绑定:后台任务
 * 与内部 RPC 正是以重建出来的主体做判定(P2-D),这条路径必须在 P2-C 就被
 * 覆盖;线程绑定版本由 {@link AccessAuthorizationIntegrationTest} 经真实
 * 安全链覆盖。
 */
@SpringBootTest
@ActiveProfiles("test")
class ResourceIsolationIntegrationTest {

    @Autowired ProjectService projectService;
    @Autowired GlobalProjectSearchService search;
    @Autowired AuthorizationGuard guard;
    @Autowired AccessResourceRepository resources;
    @Autowired IdentityRepository identities;
    @Autowired GlobalAssistantConversationService conversations;

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

    private void member(UUID orgId, UUID userId, OrgRole role) {
        identities.upsertMembership(orgId, userId, role);
    }

    private void grantProject(UUID projectId, UUID userId, UUID orgId, ProjectRole role) {
        identities.upsertProjectMember(projectId, userId, orgId, role);
    }

    /** 以指定主体创建项目(创建归属取决于绑定主体,不是测试的偶然)。 */
    private Project createProjectAs(ActorContext actor, String title) {
        if (resources.findMembership(actor.orgId(),actor.userId()).isEmpty())
            member(actor.orgId(),actor.userId(),OrgRole.MEMBER);
        try (ActorContextHolder.Scope ignored = ActorContextHolder.bind(actor)) {
            return projectService.createProject(title + " " + UUID.randomUUID());
        }
    }

    // ------------------------------------------------------------ 项目隔离

    @Test void sameOrganizationWithoutProjectRoleCannotProbeExistenceByErrorCode() {
        UUID organization=standardOrg("same-org-hidden");
        UUID owner=user("hidden-owner"),reader=user("hidden-reader");
        member(organization,reader,OrgRole.MEMBER);
        Project hidden=createProjectAs(ActorContext.oidc(owner,organization),"Hidden project");
        try(var ignored=ActorContextHolder.bind(ActorContext.oidc(reader,organization))) {
            var known=catchThrowableOfType(()->projectService.getProjectForActor(hidden.id()),com.specagent.common.ApiException.class);
            var unknown=catchThrowableOfType(()->projectService.getProjectForActor(UUID.randomUUID()),com.specagent.common.ApiException.class);
            assertThat(known.status()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
            assertThat(unknown.status()).isEqualTo(known.status());
            assertThat(unknown.code()).isEqualTo(known.code()).isEqualTo("PROJECT_NOT_FOUND");
        }
    }
    @Test void creationCannotChooseAnOrganizationWithoutMembership() {
        UUID org=standardOrg("create-denied");
        UUID outsider=user("create-outsider");
        try(var ignored=ActorContextHolder.bind(ActorContext.oidc(outsider,org))) {
            assertThatThrownBy(()->projectService.createProject("Forbidden"))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }
    @Test void projectRoleDoesNotSurviveOrganizationMembershipRevocation() {
        UUID org=standardOrg("membership-revoke-project");
        UUID owner=user("membership-revoke-owner");
        Project project=createProjectAs(ActorContext.oidc(owner,org),"Before revocation");
        identities.updateMembershipStatus(org,owner,"DISABLED");
        assertThat(guard.projectDecisionFor(ActorContext.oidc(owner,org),project.id(),Capability.PROJECT_READ).reasonCode())
                .isEqualTo(AccessReason.MEMBERSHIP_DISABLED);
        assertThat(guard.projectDecisionFor(ActorContext.oidc(owner,org),project.id(),Capability.PROJECT_WRITE).allowed()).isFalse();
    }

    @Test void titleAvailabilityCannotRevealAnInvisibleProjectInTheSameOrganization() {
        UUID org=standardOrg("title-isolation");
        UUID owner=user("title-owner"),other=user("title-other");
        member(org,owner,OrgRole.MEMBER);member(org,other,OrgRole.MEMBER);
        Project privateProject=createProjectAs(ActorContext.oidc(owner,org),"Private title");
        try(var ignored=ActorContextHolder.bind(ActorContext.oidc(other,org))) {
            assertThatCode(()->projectService.requireTitleAvailable(privateProject.title())).doesNotThrowAnyException();
            Project sameTitle=projectService.createProject(privateProject.title());
            assertThat(sameTitle.id()).isNotEqualTo(privateProject.id());
            assertThat(projectService.listProjects(null)).extracting(Project::id).containsExactly(sameTitle.id());
        }
    }

    @Test
    void projectInAnotherOrganizationIsInvisible() {
        UUID orgA = standardOrg("iso-a");
        UUID orgB = standardOrg("iso-b");
        UUID actorUser = user("iso-a-user");
        member(orgA, actorUser, OrgRole.MEMBER);
        ActorContext actor = ActorContext.oidc(actorUser, orgA);

        UUID projectInB = createProjectAs(ActorContext.oidc(user("iso-b-user"), orgB), "跨组织项目").id();

        AccessDecision decision = guard.projectDecisionFor(actor, projectInB, Capability.PROJECT_READ);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode())
                .as("跨组织访问必须与「项目不存在」不可区分")
                .isEqualTo(AccessReason.RESOURCE_NOT_VISIBLE);

        AccessDeniedException denied = catchThrowableOfType(
                () -> guard.requireProjectFor(actor, projectInB, Capability.PROJECT_READ),
                AccessDeniedException.class);
        assertThat(denied).isNotNull();
        assertThat(denied.concealed()).isTrue();
    }

    /**
     * 组织管理员不因管理角色获得项目正文读取权(设计 §5 明确禁止
     * "以管理角色暗中读取项目正文")。它必须通过治理能力显式为自己授予
     * 项目角色——那是另一次可审计的动作。
     */
    @Test
    void organizationAdminDoesNotSilentlyReadProjectContent() {
        UUID org = standardOrg("iso-admin");
        UUID admin = user("iso-admin-user");
        member(org, admin, OrgRole.ADMIN);
        UUID owner = user("iso-owner");
        member(org, owner, OrgRole.MEMBER);

        UUID projectId = createProjectAs(ActorContext.oidc(owner, org), "他人项目").id();

        AccessDecision decision = guard.projectDecisionFor(
                ActorContext.oidc(admin, org), projectId, Capability.PROJECT_READ);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo(AccessReason.RESOURCE_NOT_VISIBLE);

        try (ActorContextHolder.Scope ignored = ActorContextHolder.bind(ActorContext.oidc(admin, org))) {
            assertThatCode(() -> guard.requireProjectGovernance(projectId)).doesNotThrowAnyException();
        }
    }

    @Test
    void projectRoleDeterminesCapabilitiesDeterministically() {
        UUID org = standardOrg("iso-roles");
        UUID actorUser = user("iso-role-user");
        member(org, actorUser, OrgRole.MEMBER);
        UUID projectId = createProjectAs(ActorContext.oidc(user("iso-role-creator"), org), "角色项目").id();
        ActorContext actor = ActorContext.oidc(actorUser, org);

        grantProject(projectId, actorUser, org, ProjectRole.VIEWER);
        assertThat(guard.projectDecisionFor(actor, projectId, Capability.PROJECT_READ).allowed()).isTrue();
        assertThat(guard.projectDecisionFor(actor, projectId, Capability.PROJECT_WRITE).reasonCode())
                .isEqualTo(AccessReason.PROJECT_CAPABILITY_MISSING);
        assertThat(guard.projectDecisionFor(actor, projectId, Capability.PROJECT_MANAGE).reasonCode())
                .isEqualTo(AccessReason.PROJECT_CAPABILITY_MISSING);

        // 已冻结决定:项目重命名属于 PROJECT_MANAGE,EDITOR 不允许。
        grantProject(projectId, actorUser, org, ProjectRole.EDITOR);
        assertThat(guard.projectDecisionFor(actor, projectId, Capability.PROJECT_WRITE).allowed()).isTrue();
        assertThat(guard.projectDecisionFor(actor, projectId, Capability.PROJECT_MANAGE).allowed()).isFalse();

        grantProject(projectId, actorUser, org, ProjectRole.MANAGER);
        assertThat(guard.projectDecisionFor(actor, projectId, Capability.PROJECT_MANAGE).allowed()).isTrue();
    }

    @Test
    void projectCreatorBecomesManager() {
        UUID org = standardOrg("iso-creator");
        UUID creator = user("iso-creator-user");
        member(org, creator, OrgRole.MEMBER);

        Project project = createProjectAs(ActorContext.oidc(creator, org), "创建者项目");

        assertThat(resources.projectRole(project.id(), creator))
                .as("creator must hold a project role, else a new project is unreadable")
                .contains(ProjectRole.MANAGER);
        assertThat(guard.projectDecisionFor(ActorContext.oidc(creator, org),
                project.id(), Capability.PROJECT_MANAGE).allowed()).isTrue();
    }

    @Test
    void projectCreationResolvesAnOrganizationScopedDefaultProfile() {
        UUID org = standardOrg("iso-profile");
        UUID creator = user("iso-profile-user");
        member(org, creator, OrgRole.MEMBER);

        Project project = createProjectAs(ActorContext.oidc(creator, org), "画像项目");

        assertThat(resources.projectOrg(project.id())).contains(org);
        assertThat(project.defaultProfileId()).isNotNull();
        assertThat(resources.profileOrg(project.defaultProfileId()).orElseThrow())
                .as("the default profile must belong to the same organization")
                .isEqualTo(org);
    }

    @Test
    void disabledUserLosesProjectAccessImmediately() {
        UUID org = standardOrg("iso-disabled");
        UUID actorUser = user("iso-disabled-user");
        member(org, actorUser, OrgRole.MEMBER);
        UUID projectId = createProjectAs(ActorContext.oidc(actorUser, org), "禁用项目").id();
        ActorContext actor = ActorContext.oidc(actorUser, org);

        assertThat(guard.projectDecisionFor(actor, projectId, Capability.PROJECT_READ).allowed()).isTrue();

        identities.updateUserStatus(actorUser, "DISABLED");
        assertThat(guard.projectDecisionFor(actor, projectId, Capability.PROJECT_READ).reasonCode())
                .isEqualTo(AccessReason.USER_DISABLED);
    }

    // -------------------------------------------- 列表与搜索的候选集过滤

    /**
     * 需求原文:同组织、无项目角色的用户看不到<b>标题、数量、预览和检索
     * 结果</b>。
     *
     * 因此候选集必须在 SQL 里同时按"组织"和"项目访问权限"收缩:只按组织
     * 过滤时,同组织成员的列表长度本身就泄露了他人项目的存在与数量。
     */
    @Test
    void sameOrganizationWithoutProjectRoleSeesNothingInListOrSearch() {
        UUID org = standardOrg("iso-visibility");
        UUID viewer = user("iso-visibility-viewer");
        member(org, viewer, OrgRole.MEMBER);

        Project visible = createProjectAs(ActorContext.oidc(user("iso-visibility-owner"), org), "Alpha 可见项目");
        Project other = createProjectAs(ActorContext.oidc(user("iso-visibility-other"), org), "Bravo 他人项目");

        try (ActorContextHolder.Scope ignored = ActorContextHolder.bind(ActorContext.oidc(viewer, org))) {
            assertThat(projectService.listProjects())
                    .as("同组织但没有任何项目角色时列表必须为空")
                    .isEmpty();
            assertThat(projectService.listProjects("Alpha")).isEmpty();
            assertThat(search.search("Alpha", 5)).isEmpty();
            assertThat(search.listRecent(5)).isEmpty();
        }

        // 只授予其中一个项目的 VIEWER 角色:另一个仍然完全不可见。
        grantProject(visible.id(), viewer, org, ProjectRole.VIEWER);
        try (ActorContextHolder.Scope ignored = ActorContextHolder.bind(ActorContext.oidc(viewer, org))) {
            assertThat(projectService.listProjects()).extracting(Project::id)
                    .containsExactly(visible.id());
            assertThat(projectService.listProjects("Bravo")).isEmpty();
            assertThat(search.search("Alpha", 5))
                    .extracting(GlobalProjectSearchService.Candidate::projectId)
                    .containsExactly(visible.id());
            assertThat(search.search("Bravo", 5))
                    .as("未被授权的项目不能出现在检索结果里")
                    .isEmpty();
            assertThat(search.listRecent(5))
                    .extracting(GlobalProjectSearchService.Candidate::projectId)
                    .doesNotContain(other.id());
        }
    }

    /** 跨组织时列表与搜索也必须为空,且不因组织内无项目而报错。 */
    @Test
    void listAndSearchAreEmptyForAnOrgTheActorIsNotAMemberOf() {
        UUID orgA = standardOrg("iso-empty-a");
        UUID orgB = standardOrg("iso-empty-b");
        UUID actorUser = user("iso-empty-user");
        member(orgA, actorUser, OrgRole.MEMBER);
        createProjectAs(ActorContext.oidc(user("iso-empty-owner"), orgB), "他人组织项目");

        try (ActorContextHolder.Scope ignored = ActorContextHolder.bind(ActorContext.oidc(actorUser, orgA))) {
            assertThat(projectService.listProjects()).isEmpty();
            assertThat(search.search("他人组织", 5)).isEmpty();
        }
    }

    // ------------------------------------------------ legacy 组织不是豁免

    /**
     * 需求原文:legacy/local 组织不构成授权豁免;OIDC 用户不能借它获得
     * 所有者权限。
     *
     * 这条测试锁住的正是"按组织 id 放行"这一错误判据:把 legacy 组织的
     * 成员资格当成特权来源,会让任何被加入该组织的 OIDC 用户(哪怕是
     * 组织 OWNER)直接读取全部存量项目正文。
     */
    @Test
    void legacyOrganizationIsNotAnAuthorizationExemptionForOidcUsers() {
        UUID legacyOrg = LocalPrincipalIds.ORG_ID;
        UUID oidcUser = user("iso-legacy-oidc");
        member(legacyOrg, oidcUser, OrgRole.OWNER);
        ActorContext oidcActor = ActorContext.oidc(oidcUser, legacyOrg);

        UUID projectId = createProjectAs(
                ActorContext.local(LocalPrincipalIds.USER_ID, legacyOrg), "legacy 项目").id();

        assertThat(guard.projectDecisionFor(oidcActor, projectId, Capability.PROJECT_READ).reasonCode())
                .as("an OIDC user must not inherit owner rights from the legacy organization")
                .isEqualTo(AccessReason.RESOURCE_NOT_VISIBLE);
        assertThat(guard.projectDecisionFor(oidcActor, projectId, Capability.PROJECT_MANAGE).allowed())
                .isFalse();

        // 列表与搜索同样不能因为"同组织"而放行。
        try (ActorContextHolder.Scope ignored = ActorContextHolder.bind(oidcActor)) {
            assertThat(projectService.listProjects())
                    .as("legacy membership alone must not reveal legacy projects")
                    .doesNotContain(projectService.getProject(projectId).orElseThrow());
            assertThat(search.listRecent(10))
                    .extracting(GlobalProjectSearchService.Candidate::projectId)
                    .doesNotContain(projectId);
        }

        // 只有受限本地身份适配器产生的 LOCAL 主体享有单机权限。
        assertThat(guard.projectDecisionFor(
                ActorContext.local(LocalPrincipalIds.USER_ID, legacyOrg), projectId,
                Capability.PROJECT_MANAGE).allowed()).isTrue();
    }

    @Test
    void disabledLocalPrincipalLosesTheSingleMachineAllowance() {
        UUID legacyOrg = LocalPrincipalIds.ORG_ID;
        UUID projectId = createProjectAs(
                ActorContext.local(LocalPrincipalIds.USER_ID, legacyOrg), "禁用单机项目").id();
        ActorContext local = ActorContext.local(LocalPrincipalIds.USER_ID, legacyOrg);

        assertThat(guard.projectDecisionFor(local, projectId, Capability.PROJECT_READ).allowed()).isTrue();

        identities.updateUserStatus(LocalPrincipalIds.USER_ID, "DISABLED");
        try {
            assertThat(guard.projectDecisionFor(local, projectId, Capability.PROJECT_READ).reasonCode())
                    .as("a disabled local principal must lose the single-machine allowance")
                    .isEqualTo(AccessReason.USER_DISABLED);
        } finally {
            identities.updateUserStatus(LocalPrincipalIds.USER_ID, "ACTIVE");
        }
    }

    /** 没有主体就没有授权:不存在匿名或"本地管理员"回落。 */
    @Test
    void unboundCallerGetsNoAuthorizationAtAll() {
        UUID legacyOrg = LocalPrincipalIds.ORG_ID;
        UUID projectId = createProjectAs(
                ActorContext.local(LocalPrincipalIds.USER_ID, legacyOrg), "无主体项目").id();

        assertThat(guard.projectDecisionFor(null, projectId, Capability.PROJECT_READ).reasonCode())
                .as("no principal means no authorization, not a local-admin fallback")
                .isEqualTo(AccessReason.UNAUTHENTICATED);

        UUID standardProject = createProjectAs(
                ActorContext.oidc(user("iso-elsewhere"), standardOrg("iso-elsewhere-org")), "标准组织项目").id();
        assertThat(guard.projectDecisionFor(null, standardProject, Capability.PROJECT_READ).reasonCode())
                .isEqualTo(AccessReason.UNAUTHENTICATED);
    }

    // ------------------------------------------------------------ 会话私有

    @Test
    void conversationIsPrivateToItsOwnerEvenInsideTheSameOrganization() {
        UUID org = standardOrg("iso-thread-org");
        UUID owner = user("iso-thread-owner");
        UUID other = user("iso-thread-other");
        member(org, owner, OrgRole.MEMBER);
        member(org, other, OrgRole.ADMIN);

        UUID threadId;
        try (ActorContextHolder.Scope ignored = ActorContextHolder.bind(ActorContext.oidc(owner, org))) {
            threadId = conversations.createThread().id();
        }

        try (ActorContextHolder.Scope ignored = ActorContextHolder.bind(ActorContext.oidc(owner, org))) {
            assertThatCode(() -> conversations.findThreadOwned(threadId)).doesNotThrowAnyException();
        }

        // 同组织的管理员也不可读取他人的私有会话——设计 §5 的明确要求。
        try (ActorContextHolder.Scope ignored = ActorContextHolder.bind(ActorContext.oidc(other, org))) {
            AccessDeniedException denied = catchThrowableOfType(
                    () -> conversations.findThreadOwned(threadId), AccessDeniedException.class);
            assertThat(denied).isNotNull();
            assertThat(denied.concealed())
                    .as("a foreign private conversation must look like it does not exist")
                    .isTrue();
        }

        UUID otherOrg = standardOrg("iso-thread-other-org");
        UUID outsider = user("iso-thread-outsider");
        member(otherOrg, outsider, OrgRole.OWNER);
        try (ActorContextHolder.Scope ignored = ActorContextHolder.bind(ActorContext.oidc(outsider, otherOrg))) {
            assertThat(catchThrowableOfType(() -> conversations.findThreadOwned(threadId),
                    AccessDeniedException.class)).isNotNull();
        }
    }

    @Test
    void conversationListIsScopedToTheCurrentOwner() {
        UUID org = standardOrg("iso-list-org");
        UUID userOne = user("iso-list-user-1");
        UUID userTwo = user("iso-list-user-2");
        member(org, userOne, OrgRole.MEMBER);
        member(org, userTwo, OrgRole.MEMBER);

        UUID threadOne;
        try (ActorContextHolder.Scope ignored = ActorContextHolder.bind(ActorContext.oidc(userOne, org))) {
            threadOne = conversations.createThread().id();
            conversations.appendUserMessage(threadOne, "只属于用户一的会话", null);
        }
        UUID threadTwo;
        try (ActorContextHolder.Scope ignored = ActorContextHolder.bind(ActorContext.oidc(userTwo, org))) {
            threadTwo = conversations.createThread().id();
            conversations.appendUserMessage(threadTwo, "只属于用户二的会话", null);
        }

        try (ActorContextHolder.Scope ignored = ActorContextHolder.bind(ActorContext.oidc(userOne, org))) {
            assertThat(conversations.listThreads()).extracting(i -> i.threadId())
                    .as("列表必须只包含当前属主的会话")
                    .contains(threadOne)
                    .doesNotContain(threadTwo);
        }
        try (ActorContextHolder.Scope ignored = ActorContextHolder.bind(ActorContext.oidc(userTwo, org))) {
            assertThat(conversations.listThreads()).extracting(i -> i.threadId())
                    .contains(threadTwo)
                    .doesNotContain(threadOne);
        }
    }

    // -------------------------------------------------- 归属回落只存在于 LOCAL

    /**
     * "未绑定主体"的回落<b>只存在于 LOCAL 模式</b>。
     *
     * 这条断言把用户提出的要求变成可执行的门禁:OIDC 部署下,任何不绑定
     * 主体的调用都解析失败,因此 OIDC 用户不可能通过"不绑定主体"绕到本地
     * 特权上;而 LOCAL 模式回落到的是受限本地身份适配器产生的显式主体
     * (有真实行列、可被禁用),不是匿名,也不是服务超级权限。
     */
    @Test
    void unboundAttributionFailsClosedOutsideLocalMode() {
        assertThat(catchThrowableOfType(
                () -> OwnershipAttribution.currentOrDefault(AccessMode.OIDC),
                AccessDeniedException.class))
                .as("OIDC must have no unbound-principal fallback at all")
                .isNotNull();

        ActorContext local = OwnershipAttribution.currentOrDefault(AccessMode.LOCAL);
        assertThat(local.source()).isEqualTo(ActorSource.LOCAL);
        assertThat(local.userId()).isEqualTo(LocalPrincipalIds.USER_ID);
        assertThat(local.orgId()).isEqualTo(LocalPrincipalIds.ORG_ID);
        // 归属回落不等于授权:显式主体 API 传 null 仍然一律拒绝。
        assertThat(guard.projectDecisionFor(null, LocalPrincipalIds.ORG_ID, null).allowed())
                .as("attribution fallback must not leak into the explicit-principal decision")
                .isFalse();
    }
}

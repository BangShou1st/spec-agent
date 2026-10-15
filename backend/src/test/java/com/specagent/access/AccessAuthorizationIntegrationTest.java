package com.specagent.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.specagent.access.identity.IdentityRepository;
import com.specagent.access.identity.IdentityService;
import com.specagent.access.identity.SecurityModeAdmission;
import com.specagent.access.identity.SecurityModeRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 文件名:AccessAuthorizationIntegrationTest.java
 *
 * 测试目标:P2-B 的授权判定、身份/组织事实与授权范围锁在<b>真实
 * PostgreSQL</b> 上的行为。全部断言针对稳定原因码与判定结果,而不是
 * 只断言"某处返回了 403"——验收矩阵要求能区分未认证、未选组织、非成员、
 * 成员被停用、角色不足、资源不可见等不同失败。
 *
 * 覆盖的第二阶段冻结决定:
 * <ul>
 *   <li>项目重命名属于 PROJECT_MANAGE,EDITOR 不允许;</li>
 *   <li>最后一个 OWNER 不允许降级或退出,且该判定在撤权事务协议下完成;</li>
 *   <li>内部 token(服务主体)绝不单独构成业务授权;</li>
 *   <li>用户禁用/成员停用<b>每次判定</b>都重新生效,不依赖会话缓存;</li>
 *   <li>资源不可见对外表现为 404 语义({@code concealed=true}),不泄露
 *       "存在但无权"。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccessAuthorizationIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired AuthorizationGuard guard;
    @Autowired AuthorizationScopeRepository scopes;
    @Autowired AccessResourceRepository resources;
    @Autowired IdentityService identity;
    @Autowired IdentityRepository identities;
    @Autowired SecurityModeAdmission admission;
    @Autowired SecurityModeRepository modes;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    // ------------------------------------------------------------- 辅助

    private UUID newOrg(String name) {
        UUID id = UUID.randomUUID();
        identities.insertOrganizationIfAbsent(id, name, "STANDARD");
        return id;
    }

    private UUID newUser(String name) {
        UUID id = UUID.randomUUID();
        identities.insertUserIfAbsent(id, name);
        return id;
    }

    private void member(UUID orgId, UUID userId, OrgRole role) {
        identities.upsertMembership(orgId, userId, role);
    }

    private AccessDecision decide(ActorContext actor, UUID orgId, Capability capability) {
        return guard.canUseOrg(actor, orgId, capability);
    }

    // ------------------------------------------------- 本地模式与 /api/me

    @Test
    void localModeBindsAnExplicitLocalActorRatherThanAnonymousAdmin() {
        ActorContext actor = identity.localActor();

        assertThat(actor.source()).isEqualTo(ActorSource.LOCAL);
        assertThat(actor.userId()).isNotNull();
        assertThat(actor.orgId()).isNotNull();

        var membership = resources.findMembership(actor.orgId(), actor.userId());
        assertThat(membership).as("the local actor must have a real membership row").isPresent();
        assertThat(membership.orElseThrow().role()).isEqualTo(OrgRole.OWNER);
        assertThat(membership.orElseThrow().orgStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void meEndpointReportsTheLocalIdentityWithoutRequiringLogin() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("LOCAL"))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.orgSelectionRequired").value(false))
                .andExpect(jsonPath("$.orgRole").value("OWNER"))
                .andExpect(jsonPath("$.userId").isNotEmpty())
                .andExpect(jsonPath("$.orgId").isNotEmpty())
                .andExpect(jsonPath("$.orgCapabilities", Matchers.hasItems(
                        "ORG_MANAGE", "ORG_OWNER", "CREDENTIAL_MANAGE")))
                .andExpect(jsonPath("$.memberships", Matchers.hasSize(1)));
    }

    @Test
    void sessionAndCsrfBootstrapEndpointsWorkInLocalMode() throws Exception {
        mockMvc.perform(get("/api/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("LOCAL"))
                .andExpect(jsonPath("$.requiresLogin").value(false))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.orgSelected").value(true));

        // 本地模式关闭 CSRF:引导端点必须明确回答"不需要 token",而不是
        // 因为无法解析 CsrfToken 参数而报错。
        mockMvc.perform(get("/api/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.required").value(false))
                .andExpect(jsonPath("$.token").value(""));
    }

    /** /api/me 的契约绝不能夹带凭证明文、内部 secret 或模型内部类型。 */
    @Test
    void meResponseDoesNotLeakCredentialOrSecretMaterial() throws Exception {
        String body = mockMvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain("internal-secret")
                .doesNotContain("dev-internal-secret")
                .doesNotContain("credentialRef")
                .doesNotContain("master-key");
    }

    /**
     * 健康端点必须在<b>真实安全链</b>下不被认证质询。
     *
     * 这条断言存在的理由:{@code HealthControllerTest} 是 @WebMvcTest 切片,
     * 它不加载 access.security 下的真实链,因此无法证明可达性——安全进入
     * classpath 后那个切片反而会被 Boot 的默认安全链拦成 401。可达性只能在
     * 加载真实链的集成测试里验证。
     *
     * 断言的是"未被 401/403 质询"而不是 200:健康端点在 worker 门控未开放时
     * 会<b>合法地</b>返回 503(WORKER_POLLING_PENDING),那是业务语义,
     * 与安全边界无关。把它写成 200 会把两件事混为一谈。
     */
    @Test
    void healthEndpointIsNotChallengedByTheRealSecurityChain() throws Exception {
        var response = mockMvc.perform(get("/api/health")).andReturn().getResponse();
        assertThat(response.getStatus())
                .as("the security chain must not challenge /api/health")
                .isNotEqualTo(401)
                .isNotEqualTo(403);
        assertThat(response.getContentType()).contains("application/json");
    }

    /** 内部服务链不创建会话:内部端点不能因为被调用而留下浏览器会话事实。 */
    @Test
    void internalServiceChainDoesNotCreateSessions() throws Exception {
        var result = mockMvc.perform(get("/internal/v1/retrieval-store/health"))
                .andReturn();
        assertThat(result.getRequest().getSession(false))
                .as("the internal service chain must stay stateless")
                .isNull();
    }

    // ----------------------------------------------------- 角色 -> 能力矩阵

    @Test
    void organizationRolesMapToDeterministicCapabilities() {
        assertThat(OrgRole.OWNER.grants(Capability.ORG_MANAGE)).isTrue();
        assertThat(OrgRole.OWNER.grants(Capability.ORG_OWNER)).isTrue();
        assertThat(OrgRole.OWNER.grants(Capability.CREDENTIAL_MANAGE)).isTrue();

        assertThat(OrgRole.ADMIN.grants(Capability.ORG_MANAGE)).isTrue();
        assertThat(OrgRole.ADMIN.grants(Capability.CREDENTIAL_MANAGE)).isTrue();
        assertThat(OrgRole.ADMIN.grants(Capability.ORG_OWNER))
                .as("only an OWNER may transfer ownership or delete the organization")
                .isFalse();

        assertThat(OrgRole.MEMBER.grants(Capability.ORG_MANAGE)).isFalse();
        assertThat(OrgRole.MEMBER.grants(Capability.CREDENTIAL_MANAGE)).isFalse();
    }

    /** 冻结决定:项目重命名属于 PROJECT_MANAGE,EDITOR 不允许。 */
    @Test
    void projectRolesMapToDeterministicCapabilitiesWithRenameRestrictedToManage() {
        assertThat(ProjectRole.MANAGER.grants(Capability.PROJECT_MANAGE)).isTrue();
        assertThat(ProjectRole.EDITOR.grants(Capability.PROJECT_WRITE)).isTrue();
        assertThat(ProjectRole.EDITOR.grants(Capability.CREDENTIAL_USE)).isTrue();
        assertThat(ProjectRole.EDITOR.grants(Capability.PROJECT_MANAGE))
                .as("project rename is PROJECT_MANAGE; EDITOR must not be allowed")
                .isFalse();

        assertThat(ProjectRole.VIEWER.grants(Capability.PROJECT_READ)).isTrue();
        assertThat(ProjectRole.VIEWER.grants(Capability.PROJECT_WRITE)).isFalse();
        assertThat(ProjectRole.VIEWER.grants(Capability.CREDENTIAL_USE))
                .as("a viewer gets only read capability, not tool/credential use")
                .isFalse();
    }

    @Test
    void memberLevelActionsRequireOnlyMembershipWhileAdminActionsRequireTheRole() {
        UUID orgId = newOrg("p2b-caps");
        UUID admin = newUser("p2b-admin");
        UUID plain = newUser("p2b-member");
        member(orgId, admin, OrgRole.ADMIN);
        member(orgId, plain, OrgRole.MEMBER);

        assertThat(decide(ActorContext.oidc(admin, orgId), orgId, Capability.CREDENTIAL_MANAGE).allowed())
                .isTrue();
        assertThat(decide(ActorContext.oidc(admin, orgId), orgId, Capability.ORG_OWNER).allowed())
                .isFalse();
        assertThat(decide(ActorContext.oidc(admin, orgId), orgId, Capability.ORG_OWNER).reasonCode())
                .isEqualTo(AccessReason.ORG_CAPABILITY_MISSING);

        // 创建项目是成员级动作(能力为 null 表示只要求有效成员资格)。
        assertThat(decide(ActorContext.oidc(plain, orgId), orgId, null).allowed()).isTrue();
        assertThat(decide(ActorContext.oidc(plain, orgId), orgId, Capability.ORG_MANAGE).allowed())
                .isFalse();
    }

    // ------------------------------------------------------- 失败原因区分

    @Test
    void disabledUserLosesAccessOnEveryCheckAndRegainsItWhenReEnabled() {
        UUID orgId = newOrg("p2b-disable");
        UUID userId = newUser("p2b-disabled-user");
        member(orgId, userId, OrgRole.MEMBER);

        assertThat(decide(ActorContext.oidc(userId, orgId), orgId, null).allowed()).isTrue();

        identity.disableUser(userId);

        assertThat(decide(ActorContext.oidc(userId, orgId), orgId, null).reasonCode())
                .as("a disabled user must fail every subsequent check, not just at login")
                .isEqualTo(AccessReason.USER_DISABLED);
        assertThat(resources.findMembership(orgId, userId).orElseThrow().active())
                .as("user-level revocation must also disable the memberships")
                .isFalse();

        // 恢复用户与成员资格后可再次通过:判定每次都重读事实,不缓存结论。
        identities.updateUserStatus(userId, "ACTIVE");
        identities.updateMembershipStatus(orgId, userId, "ACTIVE");
        assertThat(decide(ActorContext.oidc(userId, orgId), orgId, null).allowed()).isTrue();
    }

    @Test
    void nonMemberDisabledMemberAndDisabledOrganizationAreDistinguishable() {
        UUID orgId = newOrg("p2b-reasons");
        UUID userId = newUser("p2b-reasons-user");
        UUID outsider = newUser("p2b-outsider");

        assertThat(decide(ActorContext.oidc(outsider, orgId), orgId, null).reasonCode())
                .isEqualTo(AccessReason.NOT_ORG_MEMBER);

        member(orgId, userId, OrgRole.MEMBER);
        identities.updateMembershipStatus(orgId, userId, "DISABLED");
        assertThat(decide(ActorContext.oidc(userId, orgId), orgId, null).reasonCode())
                .isEqualTo(AccessReason.MEMBERSHIP_DISABLED);

        identities.updateMembershipStatus(orgId, userId, "ACTIVE");
        jdbc.update("UPDATE organizations SET status = 'DISABLED' WHERE id = :id",
                Map.of("id", orgId));
        assertThat(decide(ActorContext.oidc(userId, orgId), orgId, null).reasonCode())
                .isEqualTo(AccessReason.ORG_DISABLED);
    }

    @Test
    void missingOrganizationSelectionIsExplicitRatherThanSilentlyGuessed() {
        UUID userId = newUser("p2b-multi-org-user");
        UUID orgA = newOrg("p2b-multi-a");
        UUID orgB = newOrg("p2b-multi-b");
        member(orgA, userId, OrgRole.MEMBER);
        member(orgB, userId, OrgRole.MEMBER);

        assertThat(identity.memberships(userId)).hasSize(2);
        assertThat(decide(ActorContext.oidc(userId, null), null, null).reasonCode())
                .isEqualTo(AccessReason.ORG_SELECTION_REQUIRED);

        AccessDeniedException denied = catchThrowableOfType(
                () -> guard.requireSelectedOrg(ActorContext.oidc(userId, null), null),
                AccessDeniedException.class);
        assertThat(denied.reasonCode()).isEqualTo(AccessReason.ORG_SELECTION_REQUIRED);
        assertThat(denied.concealed())
                .as("a missing organization selection is 403, not a hidden 404")
                .isFalse();
    }

    @Test
    void unknownOrganizationIsConcealedSoExistenceIsNotDisclosed() {
        UUID orgId = newOrg("p2b-concealed");
        UUID userId = newUser("p2b-concealed-user");
        member(orgId, userId, OrgRole.MEMBER);

        AccessDeniedException denied = catchThrowableOfType(
                () -> guard.requireOrg(ActorContext.oidc(userId, orgId), UUID.randomUUID(), null),
                AccessDeniedException.class);
        assertThat(denied.reasonCode()).isEqualTo(AccessReason.RESOURCE_NOT_VISIBLE);
        assertThat(denied.concealed()).isTrue();
    }

    // ------------------------------------------------- 服务/系统主体不提升

    @Test
    void authenticatedInternalServiceIsNotABusinessAuthorization() {
        UUID orgId = newOrg("p2b-service");
        assertThat(decide(ActorContext.service(), orgId, null).reasonCode())
                .as("a valid internal token only proves the caller is a trusted service")
                .isEqualTo(AccessReason.SERVICE_NOT_AUTHORIZED);
    }

    @Test
    void systemActorIsConfinedToItsRegisteredOrganization() {
        UUID ownOrg = newOrg("p2b-system-own");
        UUID otherOrg = newOrg("p2b-system-other");

        assertThat(decide(ActorContext.system(ownOrg), ownOrg, null).allowed()).isTrue();
        assertThat(decide(ActorContext.system(ownOrg), otherOrg, null).reasonCode())
                .isEqualTo(AccessReason.SYSTEM_SCOPE_EXCEEDED);
        assertThat(decide(ActorContext.system(ownOrg), ownOrg, Capability.ORG_MANAGE).reasonCode())
                .as("a system actor carries a narrow service grant, never an org role")
                .isEqualTo(AccessReason.SYSTEM_SCOPE_EXCEEDED);
    }

    // ------------------------------------------------------- 最后一个 OWNER

    @Test
    void lastActiveOwnerCannotBeDemotedOrRemoved() {
        UUID orgId = newOrg("p2b-owner");
        UUID owner = newUser("p2b-owner-1");
        member(orgId, owner, OrgRole.OWNER);

        AccessDeniedException demote = catchThrowableOfType(
                () -> identity.changeMembershipRole(orgId, owner, OrgRole.MEMBER),
                AccessDeniedException.class);
        assertThat(demote.reasonCode()).isEqualTo(AccessReason.LAST_OWNER_PROTECTED);

        AccessDeniedException leave = catchThrowableOfType(
                () -> identity.removeMembership(orgId, owner),
                AccessDeniedException.class);
        assertThat(leave.reasonCode()).isEqualTo(AccessReason.LAST_OWNER_PROTECTED);

        // 第二个 OWNER 存在后,降级是被允许的,并且事实确实更新。
        UUID second = newUser("p2b-owner-2");
        member(orgId, second, OrgRole.OWNER);
        assertThatCode(() -> identity.changeMembershipRole(orgId, owner, OrgRole.MEMBER))
                .doesNotThrowAnyException();
        assertThat(resources.countActiveOwners(orgId)).isEqualTo(1);
        assertThat(resources.findMembership(orgId, owner).orElseThrow().role())
                .isEqualTo(OrgRole.MEMBER);
    }

    // --------------------------------------------------- 授权范围锁与版本

    @Test
    void authorizationScopesAreEnsuredLockedInAscendingOrderAndVersioned() {
        UUID orgId = newOrg("p2b-scope");
        UUID userId = newUser("p2b-scope-user");
        List<String> keys = AuthorizationScopes.sorted(List.of(
                AuthorizationScopes.project(UUID.randomUUID()),
                AuthorizationScopes.org(orgId),
                AuthorizationScopes.user(userId),
                AuthorizationScopes.thread(UUID.randomUUID())));

        assertThat(keys).as("取锁顺序必须按 scope_key 升序").isSorted();
        assertThat(keys).allMatch(key -> key.startsWith("authz:"));

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        List<String> locked = tx.execute(status -> guard.lockSharedFailClosed(keys));
        assertThat(locked).containsExactlyElementsOf(keys);

        assertThat(guard.scopes().versions(keys).values()).containsOnly(0L);
        guard.scopes().bumpVersions(keys);
        assertThat(guard.scopes().versions(keys).values()).containsOnly(1L);

        // 幂等预建:重复 ensure 不改变既有版本。
        guard.scopes().ensure(keys);
        assertThat(guard.scopes().versions(keys).values()).containsOnly(1L);
    }

    @Test
    void membershipChangeBumpsTheAuthorizationVersionsOfOrgAndUser() {
        UUID orgId = newOrg("p2b-bump");
        UUID userId = newUser("p2b-bump-user");

        identity.addMember(orgId, userId, OrgRole.MEMBER);

        assertThat(guard.scopes().version(AuthorizationScopes.org(orgId))).contains(1L);
        assertThat(guard.scopes().version(AuthorizationScopes.user(userId))).contains(1L);
    }

    // ------------------------------------------------------- 审计与准入

    @Test
    void denialsAreAuditedWithAStableReasonCode() {
        UUID orgId = newOrg("p2b-audit");
        UUID userId = newUser("p2b-audit-user");
        member(orgId, userId, OrgRole.MEMBER);

        AccessDeniedException denied = catchThrowableOfType(
                () -> guard.requireOrg(ActorContext.oidc(userId, orgId), orgId, Capability.ORG_MANAGE),
                AccessDeniedException.class);
        assertThat(denied.reasonCode()).isEqualTo(AccessReason.ORG_CAPABILITY_MISSING);

        Integer audited = jdbc.queryForObject("""
                SELECT COUNT(*) FROM security_audit_events
                WHERE actor_user_id = :actor AND reason_code = 'ORG_CAPABILITY_MISSING'
                """, Map.of("actor", userId), Integer.class);
        assertThat(audited).isEqualTo(1);
    }

    @Test
    void securityModeAdmissionRejectsDatabaseRegistrationMismatch() {
        SecurityModeRepository.RegisteredMode original = modes.find().orElseThrow();
        try {
            modes.update("OIDC", 1, "https://issuer.example");
            assertThat(org.assertj.core.api.Assertions.catchThrowable(admission::verify))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Security mode admission refused");
        } finally {
            modes.update(original.mode(), original.protocolVersion(), original.issuerUri());
            admission.verify();
        }
    }
}

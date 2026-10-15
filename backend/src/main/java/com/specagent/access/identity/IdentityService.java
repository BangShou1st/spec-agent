package com.specagent.access.identity;

import com.specagent.access.AccessMode;
import com.specagent.access.AccessReason;
import com.specagent.access.AccessResourceRepository;
import com.specagent.access.AccessDeniedException;
import com.specagent.access.ActorContext;
import com.specagent.access.AuthorizationGuard;
import com.specagent.access.AuthorizationScopes;
import com.specagent.access.IdentityModeProperties;
import com.specagent.access.OrgRole;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文件名:IdentityService.java
 *
 * 用途:身份解析、组织成员管理与本地模式引导。
 *
 * 三个刻意的设计:
 *
 * <ol>
 *   <li><b>身份 -> user id 是确定性的</b>(由 issuer+subject 派生)。并发首登
 *       的两个事务算出同一个 id,因此既不需要分布式锁,也不会留下孤儿用户。</li>
 *   <li><b>LOCAL 模式创建显式的本地 user 与 org</b>。本地模式的"无登录"
 *       因此不是匿名管理员,而是一个有明确归属、可被禁用、可被审计的普通
 *       主体。缺少身份上下文不会被解释成高权限。</li>
 *   <li><b>成员变更走撤权事务协议</b>(第二阶段 §8):先对
 *       {@code authz:org:{orgId}} 取排他授权锁,再读取最新事实做判定,
 *       最后更新事实并递增版本。这样"最后一个 OWNER"这类跨行不变量在并发
 *       降级下仍然成立,而不是靠一次没有锁的 COUNT 去赌。</li>
 * </ol>
 *
 * 读取路径刻意<b>不加事务</b>:登录与本地主体解析是每请求调用的,长连接
 * (SSE) 上开事务会长期占用连接池。每条写入语句本身是幂等的,因此不需要
 * 事务来保证正确性。
 */
@Service
public class IdentityService {

    /**
     * 本地组织/用户与存量(legacy)组织/用户使用<b>同一组固定标识</b>,
     * 常量定义在中性 {@link com.specagent.access.LocalPrincipalIds}——
     * 授权判定也要用到它,而它不能反向依赖 identity 包。
     *
     * 该组标识必须与 V61 迁移中的引导行逐字一致;多人部署时由管理员在维护
     * 窗口内把 legacy 对象离线审核后显式分配给真实组织,绝不自动分配。
     */
    static final UUID LOCAL_ORG_ID = com.specagent.access.LocalPrincipalIds.ORG_ID;
    static final UUID LOCAL_USER_ID = com.specagent.access.LocalPrincipalIds.USER_ID;
    static final UUID LEGACY_ORG_ID = com.specagent.access.LocalPrincipalIds.ORG_ID;
    static final UUID LEGACY_USER_ID = com.specagent.access.LocalPrincipalIds.USER_ID;

    private final IdentityRepository identities;
    private final AccessResourceRepository resources;
    private final IdentityModeProperties properties;
    private final AuthorizationGuard guard;
    private com.specagent.access.AuthorizationRevocationOutbox revocations;
    @org.springframework.beans.factory.annotation.Autowired
    public void setRevocations(com.specagent.access.AuthorizationRevocationOutbox revocations) {
        this.revocations=revocations;
    }

    public IdentityService(IdentityRepository identities, AccessResourceRepository resources,
                           IdentityModeProperties properties, AuthorizationGuard guard) {
        this.identities = identities;
        this.resources = resources;
        this.properties = properties;
        this.guard = guard;
    }

    /**
     * 由外部身份解析(或首次创建)本地用户。
     *
     * @return 用户 id
     */
    public UUID resolveOrCreateUser(String issuer, String subject, String displayName) {
        // 常见路径是"已经解析过":只做一次 SELECT,避免每请求两次无谓写入。
        Optional<UUID> existing = identities.findUserIdByIssuerSubject(issuer, subject);
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID expected = deterministicUserId(issuer, subject);
        String name = (displayName == null || displayName.isBlank()) ? subject : displayName;
        identities.insertUserIfAbsent(expected, name);
        identities.insertIdentityIfAbsent(
                UUID.nameUUIDFromBytes(("spec-agent:identity:" + issuer + "\u0000" + subject)
                        .getBytes(StandardCharsets.UTF_8)),
                expected, issuer, subject);
        // 以数据库为准回读:并发首登时另一个事务可能已写入同一身份。
        return identities.findUserIdByIssuerSubject(issuer, subject).orElse(expected);
    }

    /** 由身份派生的确定性用户标识(与数据库无关,纯函数)。 */
    public static UUID deterministicUserId(String issuer, String subject) {
        return UUID.nameUUIDFromBytes(("spec-agent:user:" + issuer + "\u0000" + subject)
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * LOCAL 模式的显式身份。初始事实由 V61 一次性建立；请求解析只读。
     * 绝不恢复管理员已禁用、删除或降级的成员关系，权限仍由实时事实决定。
     */
    public ActorContext localActor() {
        if (properties.accessMode()!=com.specagent.access.AccessMode.LOCAL) {
            throw com.specagent.access.AccessDeniedException.forbidden(
                    com.specagent.access.AccessReason.UNAUTHENTICATED,"Local identity is disabled in OIDC mode");
        }
        return ActorContext.local(LOCAL_USER_ID, LOCAL_ORG_ID);
    }

    /**
     * 组织、用户与成员的<b>只读视图</b>。
     *
     * 刻意不向调用方暴露仓储类型:HTTP 边界不得直接接触 Repository(结构
     * 门禁),而这些视图同时是前端契约与审计面共用的形状。
     */
    public record OrgView(UUID id, String name, String kind) {
    }

    public record UserView(UUID id, String displayName, String status) {
    }

    public record MembershipView(UUID orgId, String orgName, OrgRole role) {
    }

    public List<MembershipView> memberships(UUID userId) {
        return resources.findActiveMemberships(userId).stream()
                .map(m -> new MembershipView(m.orgId(), m.orgName(), m.role()))
                .toList();
    }

    public Optional<OrgView> organization(UUID orgId) {
        return resources.findOrganization(orgId)
                .map(o -> new OrgView(o.id(), o.name(), o.kind()));
    }

    public Optional<UserView> user(UUID userId) {
        return resources.findUser(userId)
                .map(u -> new UserView(u.id(), u.displayName(), u.status()));
    }

    public AccessMode mode() {
        return properties.accessMode();
    }

    /**
     * 组织成员角色变更。
     *
     * 撤权事务协议:先取 {@code authz:org:{orgId}} 排他授权锁 -> 读取最新
     * 事实 -> 判定 -> 更新事实与版本。锁<b>只</b>覆盖授权范围与授权事实,
     * 不覆盖任务取消(那由控制面按 task 锁顺序处理),因此不会与提交链形成
     * {@code authorization -> task} 的锁环。
     */
    @Transactional
    public void changeMembershipRole(UUID orgId, UUID userId, OrgRole newRole) {
        guard.lockExclusiveFailClosed(List.of(AuthorizationScopes.org(orgId)));
        AccessResourceRepository.MembershipFacts existing =
                resources.findMembership(orgId, userId).orElse(null);
        if (existing == null) {
            throw AccessDeniedException.forbidden(AccessReason.NOT_ORG_MEMBER,
                    "Target user is not a member of this organization");
        }
        boolean losingOwner = existing.role() == OrgRole.OWNER && newRole != OrgRole.OWNER;
        if (losingOwner && resources.countActiveOwners(orgId) <= 1) {
            throw AccessDeniedException.forbidden(AccessReason.LAST_OWNER_PROTECTED,
                    "The last active OWNER cannot be demoted or removed");
        }
        identities.upsertMembership(orgId, userId, newRole);
        revocations.append(userId,orgId);
        guard.scopes().bumpVersions(List.of(
                AuthorizationScopes.org(orgId), AuthorizationScopes.user(userId)));
    }

    /** 退出组织。同样受"最后一个 OWNER"约束,同样走撤权事务协议。 */
    @Transactional
    public void removeMembership(UUID orgId, UUID userId) {
        guard.lockExclusiveFailClosed(List.of(AuthorizationScopes.org(orgId)));
        AccessResourceRepository.MembershipFacts existing =
                resources.findMembership(orgId, userId).orElse(null);
        if (existing == null) {
            return;
        }
        if (existing.role() == OrgRole.OWNER && resources.countActiveOwners(orgId) <= 1) {
            throw AccessDeniedException.forbidden(AccessReason.LAST_OWNER_PROTECTED,
                    "The last active OWNER cannot leave the organization");
        }
        identities.updateMembershipStatus(orgId, userId, "DISABLED");
        revocations.append(userId,orgId);
        guard.scopes().bumpVersions(List.of(
                AuthorizationScopes.org(orgId), AuthorizationScopes.user(userId)));
    }

    @Transactional
    public void addMember(UUID orgId, UUID userId, OrgRole role) {
        guard.lockExclusiveFailClosed(List.of(AuthorizationScopes.org(orgId)));
        identities.upsertMembership(orgId, userId, role);
        guard.scopes().bumpVersions(List.of(
                AuthorizationScopes.org(orgId), AuthorizationScopes.user(userId)));
    }

    /** 用户级撤权:禁用用户并递增其授权版本(其全部组织资格同时失效)。 */
    @Transactional
    public void disableUser(UUID userId) {
        List<UUID> orgIds = resources.findActiveMemberships(userId).stream()
                .map(AccessResourceRepository.MembershipFacts::orgId)
                .toList();
        guard.lockExclusiveFailClosed(AuthorizationScopes.forUser(userId, orgIds));
        identities.updateUserStatus(userId, "DISABLED");
        identities.updateMembershipStatusForAllOrgs(userId, "DISABLED");
        revocations.append(userId,null);
        guard.scopes().bumpVersions(AuthorizationScopes.forUser(userId, orgIds));
    }
}

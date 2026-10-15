package com.specagent.access;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 文件名:AuthorizationGuard.java
 *
 * 用途:授权判定的唯一编排入口(第二阶段 §1.1 的六条合取)。
 *
 * 判定表达式:
 * <pre>
 *   1 身份有效(用户启用)
 *   2 主体上下文来自持久化事实
 *   3 组织成员资格有效
 *   4 资源归属与上下文一致
 *   5 能力(角色 -> 能力)允许目标动作
 *   6 执行授权(仅执行路径):执行 token 有效 AND 动作符合 Runtime 业务规则
 * </pre>
 * 1–5 对读与写一律成立;第 6 条由执行侧(P1 的 TaskWriteGuard)叠加。
 * <b>缺少任何一条即拒绝,不存在降级放行。</b>
 *
 * 为什么判定收敛在这里:设计明确"避免只加几个 {@code @PreAuthorize}
 * 就漏掉 Worker/RPC"。Worker、内部 RPC、检索、工具都调用本类,因此
 * 权限覆盖不依赖 HTTP 层是否记得加注解。
 */
@Service
public class AuthorizationGuard {

    private final AccessResourceRepository resources;
    private final AuthorizationScopeRepository scopes;
    private final AccessAuditRecorder audit;
    private final IdentityModeProperties properties;

    public AuthorizationGuard(AccessResourceRepository resources,
                              AuthorizationScopeRepository scopes,
                              AccessAuditRecorder audit,
                              IdentityModeProperties properties) {
        this.resources = resources;
        this.scopes = scopes;
        this.audit = audit;
        this.properties = properties;
    }

    /** 当前线程绑定的主体。缺失即未认证——绝不回退到匿名管理员。 */
    public ActorContext current() {
        return ActorContextHolder.require();
    }

    public ActorContext currentOrNull() {
        return ActorContextHolder.current();
    }

    // ------------------------------------------------------------------ 判定

    /**
     * 纯判定,不抛异常。供需要分支的调用方(例如"可见则展示、不可见则
     * 从列表里剔除")与测试使用。
     */
    public AccessDecision canUseOrg(ActorContext actor, UUID orgId, Capability capability) {
        if (actor == null) {
            return AccessDecision.deny(AccessReason.UNAUTHENTICATED);
        }
        if (actor.source()==ActorSource.LOCAL && (properties.accessMode()!=AccessMode.LOCAL
                || !LocalPrincipalIds.USER_ID.equals(actor.userId())
                || !LocalPrincipalIds.ORG_ID.equals(actor.orgId()))) {
            return AccessDecision.deny(AccessReason.UNAUTHENTICATED);
        }
        if (actor.source() == ActorSource.SERVICE) {
            // 内部 token 已验证也不够:服务主体没有业务身份。
            return AccessDecision.deny(AccessReason.SERVICE_NOT_AUTHORIZED);
        }
        if (actor.isUser()) {
            AccessResourceRepository.UserFacts user =
                    resources.findUser(actor.userId()).orElse(null);
            if (user == null) {
                return AccessDecision.deny(AccessReason.UNAUTHENTICATED);
            }
            if (!user.active()) {
                // 每次检查都重新读取状态,不依赖会话里缓存的"我是谁"。
                return AccessDecision.deny(AccessReason.USER_DISABLED);
            }
        }
        if (orgId == null) {
            return AccessDecision.deny(AccessReason.ORG_SELECTION_REQUIRED);
        }
        AccessResourceRepository.OrgFacts org = resources.findOrganization(orgId).orElse(null);
        if (org == null) {
            return AccessDecision.deny(AccessReason.RESOURCE_NOT_VISIBLE);
        }
        if (!org.active()) {
            return AccessDecision.deny(AccessReason.ORG_DISABLED);
        }
        if (actor.source() == ActorSource.SYSTEM) {
            // 窄服务权限:组织必须与其登记归属一致,且系统主体不携带
            // 组织角色能力——它的授权来自绑定到 job/source/generation 的
            // 服务 grant,而不是角色。
            if (!orgId.equals(actor.orgId())) {
                return AccessDecision.deny(AccessReason.SYSTEM_SCOPE_EXCEEDED);
            }
            if (capability != null) {
                return AccessDecision.deny(AccessReason.SYSTEM_SCOPE_EXCEEDED);
            }
            return AccessDecision.allow();
        }
        AccessResourceRepository.MembershipFacts membership =
                resources.findMembership(orgId, actor.userId()).orElse(null);
        if (membership == null) {
            return AccessDecision.deny(AccessReason.NOT_ORG_MEMBER);
        }
        if (!membership.active()) {
            return AccessDecision.deny(AccessReason.MEMBERSHIP_DISABLED);
        }
        if (capability != null && !membership.role().grants(capability)) {
            return AccessDecision.deny(AccessReason.ORG_CAPABILITY_MISSING);
        }
        return AccessDecision.allow();
    }

    /**
     * 判定并抛异常。拒绝时写审计(尽力而为:拒绝路径上数据库可能不可用,
     * 把 403 变成 500 只会掩盖真实原因)。
     *
     * @return 通过时返回 orgId,便于链式使用
     */
    public UUID requireOrg(ActorContext actor, UUID orgId, Capability capability) {
        AccessDecision decision = canUseOrg(actor, orgId, capability);
        if (decision.allowed()) {
            return orgId;
        }
        deny(actor, orgId, capability == null ? "ORG_MEMBER" : capability.name(),
                AccessResourceType.PROJECT, orgId == null ? null : orgId.toString(),
                decision.reasonCode(), AccessReason.RESOURCE_NOT_VISIBLE.equals(decision.reasonCode()));
        throw decision.reasonCode().equals(AccessReason.RESOURCE_NOT_VISIBLE)
                ? AccessDeniedException.notVisible("Organization not found")
                : AccessDeniedException.forbidden(decision.reasonCode(),
                        "Organization access denied: " + decision.reasonCode());
    }

    /** 判定主体的<b>当前选中</b>组织。未选择组织时给出明确原因码。 */
    public UUID requireSelectedOrg(ActorContext actor, Capability capability) {
        if (actor == null) {
            throw AccessDeniedException.forbidden(AccessReason.UNAUTHENTICATED,
                    "No actor context");
        }
        if (actor.orgId() == null) {
            deny(actor, null, capability == null ? "ORG_MEMBER" : capability.name(),
                    AccessResourceType.PROJECT, null, AccessReason.ORG_SELECTION_REQUIRED, false);
            throw AccessDeniedException.forbidden(AccessReason.ORG_SELECTION_REQUIRED,
                    "An organization must be selected before this operation");
        }
        return requireOrg(actor, actor.orgId(), capability);
    }

    /**
     * 便捷判定:对象归属与主体选定组织一致时通过。
     * {@code ownership == null} 表示资源不可见(不存在或无法解析父关系),
     * 一律表现为 404,绝不区分"不存在"与"无权"。
     */
    public void requireOwnership(ActorContext actor, ResourceOwnership ownership,
                                 Capability capability) {
        if (ownership == null) {
            deny(actor, null, capability == null ? "RESOURCE_ACCESS" : capability.name(),
                    AccessResourceType.PROJECT, null, AccessReason.RESOURCE_NOT_VISIBLE, true);
            throw AccessDeniedException.notVisible("Resource not found");
        }
        requireOrg(actor, ownership.orgId(), capability);
    }

    private void deny(ActorContext actor, UUID orgId, String action, AccessResourceType type,
                      String resourceId, String reasonCode, boolean concealed) {
        audit.recordQuietly(actor == null ? null : actor.userId(), orgId, action,
                type == null ? "UNKNOWN" : type.name(), resourceId,
                concealed ? "DENY" : "DENY", reasonCode);
    }

    // ------------------------------------------------------- 授权范围锁(§8)

    /**
     * 取共享授权锁并核对范围行齐全。执行事务使用。
     * 缺行意味着授权事实不可确认 -> 失败关闭,绝不放行。
     */
    public List<String> lockSharedFailClosed(Collection<String> scopeKeys) {
        return lock(scopeKeys, true);
    }

    /**
     * 取排他授权锁。撤权事务使用,且<b>只</b>锁授权范围与授权事实:
     * 不在持锁时取消 task,避免与提交链形成锁环。
     */
    public List<String> lockExclusiveFailClosed(Collection<String> scopeKeys) {
        return lock(scopeKeys, false);
    }
    /** Mixed lock modes still follow one sorted authorization order; deletion excludes project commits. */
    public void lockProjectDeletionScopes(ActorContext actor,UUID project) {
        String projectKey=AuthorizationScopes.project(project);
        var keys=AuthorizationScopes.sorted(List.of(AuthorizationScopes.org(actor.orgId()),
                AuthorizationScopes.user(actor.userId()),projectKey));
        scopes.ensure(keys);
        for(String key:keys) {
            var locked=key.equals(projectKey)?scopes.lockExclusive(List.of(key)):scopes.lockShared(List.of(key));
            if(locked.size()!=1) throw AccessDeniedException.forbidden(AccessReason.AUTHORIZATION_SCOPE_UNKNOWN,
                    "Authorization scope rows are incomplete; refusing deletion");
        }
    }

    private List<String> lock(Collection<String> scopeKeys, boolean shared) {
        List<String> sorted = AuthorizationScopes.sorted(scopeKeys);
        if (sorted.isEmpty()) {
            return List.of();
        }
        scopes.ensure(sorted);
        List<String> locked = shared ? scopes.lockShared(sorted) : scopes.lockExclusive(sorted);
        if (locked.size() != sorted.size()) {
            throw AccessDeniedException.forbidden(AccessReason.AUTHORIZATION_SCOPE_UNKNOWN,
                    "Authorization scope rows are incomplete; refusing to proceed");
        }
        return locked;
    }

    // ------------------------------------------------- 项目与会话授权(§5)

    /**
     * 归属组织:主体已绑定则用其选中组织;未绑定主体时<b>只有 LOCAL 模式</b>
     * 才回落到本地/存量组织。
     *
     * OIDC 模式下缺少主体一律拒绝——绝不让"没有身份"变成"按 legacy 组织
     * 行事"。这条规则同时保证了 HTTP 路径的行为:HTTP 永远经过主体解析,
     * 因此回落分支只对进程内夹具/种子可达。
     */
    public UUID ownershipOrg() {
        return OwnershipAttribution.currentOrgOrDefault(properties.accessMode());
    }

    /**
     * 创建归属主体:主体已绑定则用它;未绑定主体时只有 LOCAL 模式回落到
     * 显式本地主体。回落回来的仍然是<b>一个有真实行列、可禁用、可审计的主体</b>,
     * 不是匿名管理员。
     */
    public ActorContext ownershipActor() {
        return OwnershipAttribution.currentOrDefault(properties.accessMode());
    }

    /**
     * 项目能力判定。规则(与设计 §5 对齐):
     *
     * <ol>
     *   <li>跨组织一律按<b>不可见</b>处理——不泄露"存在但无权"。</li>
     *   <li>LOCAL 模式:显式本地主体即本地组织的所有者,因此对本地组织的
     *       项目通行。这是单机交付的既有语义,不是匿名放行。</li>
     *   <li>OIDC 模式:能力来自<b>项目角色</b>。组织管理员不会因此自动获得
     *       项目正文读取权(设计明确"不以管理角色暗中读取项目正文"),
     *       它需要用治理能力显式为自己授予项目角色。</li>
     *   <li>系统主体:只在其登记组织内、且不主张组织角色能力时放行。</li>
     * </ol>
     */
    public AccessDecision projectDecision(UUID projectId, Capability capability) {
        return projectDecisionFor(resolveActorOrNull(), projectId, capability);
    }

    /**
     * 线程绑定入口的主体解析。
     *
     * 已绑定主体直接使用;**未绑定时只有 LOCAL 模式**回落到受限本地身份
     * 适配器产生的显式主体——单机交付本来就只有一个主体,且它由适配器
     * 产生、有真实行列、可被禁用。OIDC 模式下解析失败并返回 null,由判定
     * 按未认证拒绝:<b>OIDC 部署下不存在任何回落</b>,因此 OIDC 用户不可能
     * 通过"不绑定主体"绕到本地特权上。
     *
     * 显式主体 API({@code projectDecisionFor} / {@code requireProjectFor})
     * 刻意<b>不</b>做这个解析:传 null 就是"没有主体",一律拒绝。后台任务与
     * 内部 RPC 必须在调用前把主体从持久化事实重建出来(P2-D),这是让权限
     * 覆盖它们的方式,而不是给它们开特例。
     */
    private ActorContext resolveActorOrNull() {
        try {
            return OwnershipAttribution.currentOrDefault(properties.accessMode());
        } catch (AccessDeniedException denied) {
            // OIDC 无主体:交给判定按未认证拒绝,不在这里抛,以免掩盖原因码。
            return null;
        }
    }

    /**
     * 显式主体的项目能力判定。
     *
     * 这个重载存在的理由是<b>非 HTTP 调用方</b>:后台任务、内部 RPC 与工具
     * 没有请求线程,它们的主体必须从持久化事实重建(P2-D),因此判定必须
     * 能接受一个显式 ActorContext,而不是只认线程绑定。HTTP 路径继续走
     * {@link #projectDecision(UUID, Capability)}。
     *
     * 规则(与设计 §5 对齐):
     * <ol>
     *   <li>跨组织一律按<b>不可见</b>处理——不泄露"存在但无权"。</li>
     *   <li>本地/存量组织:显式本地主体即该组织所有者,因此对该组织内项目
     *       通行。判据是<b>组织身份</b>而非部署模式,所以两种模式下规则一致、
     *       也可被测试覆盖。</li>
     *   <li>其他组织:能力来自<b>项目角色</b>。组织管理员不会因此获得项目
     *       正文读取权(设计明确"不以管理角色暗中读取项目正文"),它需要用
     *       治理能力显式为自己授予项目角色。</li>
     *   <li>系统主体:只在其登记组织内、且不主张组织角色能力时放行。</li>
     * </ol>
     */
    public AccessDecision projectDecisionFor(ActorContext actor, UUID projectId, Capability capability) {
        UUID projectOrg = resources.projectOrg(projectId).orElse(null);
        if (actor == null) {
            // 没有主体就没有授权。**不存在**匿名回落,也不存在"本地管理员"
            // 回落:唯一能产生 LOCAL 主体的是受限的本地身份适配器
            // (IdentityService.localActor —— 仅 LOCAL 模式、仅回环绑定、
            // 有真实可禁用的行列),它在请求路径上绑定主体,因此 HTTP 永远
            // 走不到这个分支。把"没有身份"放行,等于给后台任务与新增入口
            // 开了一个隐形的超级用户。
            return AccessDecision.deny(AccessReason.UNAUTHENTICATED);
        }
        if (actor.source() == ActorSource.SERVICE) {
            return AccessDecision.deny(AccessReason.SERVICE_NOT_AUTHORIZED);
        }
        if (projectOrg == null || !projectOrg.equals(actor.orgId())) {
            // 不存在与跨组织在这里是同一种结果:绝不通过状态码或原因码
            // 区分"没有这个项目"和"不是你的项目"。
            return AccessDecision.deny(AccessReason.RESOURCE_NOT_VISIBLE);
        }
        if (actor.source() == ActorSource.SYSTEM) {
            return capability == null
                    ? AccessDecision.allow()
                    : AccessDecision.deny(AccessReason.SYSTEM_SCOPE_EXCEEDED);
        }
        AccessResourceRepository.UserFacts user = resources.findUser(actor.userId()).orElse(null);
        if (user == null) {
            return AccessDecision.deny(AccessReason.UNAUTHENTICATED);
        }
        if (!user.active()) {
            // 禁用先于任何特权判定:disabled 的本地主体也拿不到单机权限。
            return AccessDecision.deny(AccessReason.USER_DISABLED);
        }
        if (actor.source() == ActorSource.LOCAL) {
            // 单机本地主体。判据是**主体来源**,不是组织 id:
            // 若按"组织 == 本地/存量组织"放行,任何被加入该组织的 OIDC 用户
            // 都会获得所有者权限,legacy 组织就成了一条授权豁免。
            // LOCAL 来源只能由受限的本地身份适配器产生(见上方注释)。
            return canUseOrg(actor,projectOrg,null);
        }
        AccessDecision organization=canUseOrg(actor,projectOrg,null);
        if(!organization.allowed()) return organization;
        // OIDC 主体:能力来自项目角色。组织管理员不会因此获得项目正文
        // 读取权(设计明确"不以管理角色暗中读取项目正文")。
        ProjectRole role = resources.projectRole(projectId, actor.userId()).orElse(null);
        if (role == null) {
            return AccessDecision.deny(AccessReason.RESOURCE_NOT_VISIBLE);
        }
        if (capability != null && !role.grants(capability)) {
            return AccessDecision.deny(AccessReason.PROJECT_CAPABILITY_MISSING);
        }
        return AccessDecision.allow();
    }

    /** 判定并抛异常。跨组织/不存在一律 404。 */
    public void requireProject(UUID projectId, Capability capability) {
        requireProjectFor(resolveActorOrNull(), projectId, capability);
    }

    /** 显式主体版本;供后台任务与内部 RPC 使用(见 {@link #projectDecisionFor})。 */
    public void requireProjectFor(ActorContext actor, UUID projectId, Capability capability) {
        AccessDecision decision = projectDecisionFor(actor, projectId, capability);
        if (decision.allowed()) {
            return;
        }
        audit.recordQuietly(actor == null ? null : actor.userId(), actor == null ? null : actor.orgId(),
                capability == null ? "PROJECT_ACCESS" : capability.name(),
                AccessResourceType.PROJECT.name(), projectId.toString(),
                "DENY", decision.reasonCode());
        if (AccessReason.RESOURCE_NOT_VISIBLE.equals(decision.reasonCode())) {
            throw AccessDeniedException.notVisible("Project not found");
        }
        throw AccessDeniedException.forbidden(decision.reasonCode(),
                "Project access denied: " + decision.reasonCode());
    }

    /**
     * GA 私有会话的属主判定(设计:会话默认私有,组织管理员也不能直接读取
     * 他人的私有会话)。归属或属主不匹配按不可见处理。
     */
    public void requireThreadOwnership(UUID threadId) {
        // 与项目判定同一条解析规则:LOCAL 未绑定时回落到受限本地主体,
        // OIDC 下解析为 null 并拒绝。GA 的后台组件(SSE 补发、轮次续转、
        // 孤儿恢复)在单机模式下依赖这条回落;在 OIDC 模式下它们必须在
        // P2-D 完成主体重建后才能工作——**不得**用匿名回退替代。
        requireThreadOwnershipFor(resolveActorOrNull(), threadId);
    }

    /** 显式主体版本;供后台任务与内部 RPC 使用。 */
    public void requireThreadOwnershipFor(ActorContext actor, UUID threadId) {
        AccessResourceRepository.ThreadFacts facts = resources.threadFacts(threadId).orElse(null);
        if (actor == null) {
            // 与项目判定同一条规则:没有主体就没有授权,不存在匿名回落。
            denyThread(threadId, null, AccessReason.UNAUTHENTICATED);
        }
        boolean owned = facts != null
                && facts.orgId() != null
                && facts.orgId().equals(actor.orgId())
                && actor.userId() != null
                && actor.userId().equals(facts.ownerUserId());
        if (owned) {
            requireOrg(actor, facts.orgId(), null);
            return;
        }
        denyThread(threadId, actor, AccessReason.THREAD_NOT_OWNED);
    }

    private void denyThread(UUID threadId, ActorContext actor, String reasonCode) {
        audit.recordQuietly(actor == null ? null : actor.userId(),
                actor == null ? null : actor.orgId(),
                Capability.THREAD_OWNER.name(),
                AccessResourceType.GA_THREAD.name(), threadId.toString(),
                "DENY", reasonCode);
        // 私有会话对非属主一律"不存在":不区分"会话不存在"与"不是你的会话"。
        throw AccessDeniedException.notVisible("Conversation not found");
    }

    /**
     * 列表与搜索的<b>可见范围</b>。候选生成阶段必须同时按组织和项目访问
     * 权限过滤,否则同组织内没有项目角色的用户仍能看到标题、数量与预览。
     *
     * {@code unrestrictedInOrg} 只对<b>LOCAL 来源主体</b>为真——即单机模式下
     * 由受限本地身份适配器产生的那个主体。它<b>不</b>由组织 id 推导,因此
     * OIDC 用户不可能借 legacy/local 组织拿到它。
     *
     * <b>它不是授权。</b>它只回答"候选集从哪儿取";每个候选的读取与写入
     * 仍由 {@link #projectDecisionFor} 判定。
     */
    public record VisibilityScope(UUID orgId, UUID userId, boolean unrestrictedInOrg) {
    }

    public VisibilityScope visibilityScope() {
        ActorContext actor = OwnershipAttribution.currentOrDefault(properties.accessMode());
        return new VisibilityScope(actor.orgId(), actor.userId(),
                actor.source() == ActorSource.LOCAL);
    }

    /**
     * 项目角色治理:组织 OWNER/ADMIN 可以在其组织内授予项目角色。
     * 这是<b>治理</b>能力,不包含读取项目正文——设计明确"不以管理角色暗中
     * 读取项目正文"。授予行为必须审计。
     */
    public void requireProjectGovernance(UUID projectId) {
        ActorContext actor = ownershipActor();
        UUID orgId = resources.projectOrg(projectId).orElse(null);
        if (orgId == null || !orgId.equals(actor.orgId())) {
            throw AccessDeniedException.notVisible("Project not found");
        }
        if(projectDecisionFor(actor,projectId,Capability.PROJECT_MANAGE).allowed()) return;
        requireOrg(actor,orgId,Capability.ORG_MANAGE);
        if (actor.source() == ActorSource.LOCAL) {
            return;
        }
        AccessResourceRepository.MembershipFacts membership =
                resources.findMembership(orgId, actor.userId()).orElse(null);
        if (membership == null || !membership.active()
                || !membership.role().grants(Capability.ORG_MANAGE)) {
            audit.recordQuietly(actor.userId(), orgId, "PROJECT_GOVERNANCE",
                    AccessResourceType.PROJECT.name(), projectId.toString(),
                    "DENY", AccessReason.ORG_CAPABILITY_MISSING);
            throw AccessDeniedException.forbidden(AccessReason.ORG_CAPABILITY_MISSING,
                    "Organization administration capability is required to govern project roles");
        }
    }

    public AuthorizationScopeRepository scopes() {
        return scopes;
    }
    public void lockProjectWrite(UUID projectId) {
        ActorContext actor=ownershipActor();
        lockSharedFailClosed(List.of(AuthorizationScopes.org(actor.orgId()),
                AuthorizationScopes.user(actor.userId()),AuthorizationScopes.project(projectId)));
        requireProjectFor(actor,projectId,Capability.PROJECT_WRITE);
    }

    public AccessResourceRepository resources() {
        return resources;
    }

    public AccessAuditRecorder audit() {
        return audit;
    }
}

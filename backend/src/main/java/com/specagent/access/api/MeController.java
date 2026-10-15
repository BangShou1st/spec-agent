package com.specagent.access.api;

import com.specagent.access.ActorContext;
import com.specagent.access.ActorContextHolder;
import com.specagent.access.IdentityModeProperties;
import com.specagent.access.OrgRole;
import com.specagent.access.identity.IdentityService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件名:MeController.java
 *
 * 用途:{@code GET /api/me} —— 前端的身份与会话入口(第二阶段 §6)。
 *
 * 它是新增的最小界面所依赖的唯一身份契约:登录态、当前组织、组织角色与
 * 能力集合、可切换的组织列表。前端的组织切换是<b>显式请求上下文</b>
 * (每请求头 {@code X-Spec-Agent-Org-Id}),不是一个会话内可变的活动组织,
 * 因此两个标签页可以同时处于不同组织而不互相串扰。
 *
 * 控制器只做参数转换与身份读取,资源规则一律在应用服务层校验——避免
 * "只在控制器加注解"造成 Worker/RPC 漏检。控制器也绝不直接接触仓储:
 * 组织与用户事实经 {@link IdentityService} 的只读视图暴露。
 */
@RestController
@RequestMapping("/api")
public class MeController {

    private final IdentityService identity;
    private final IdentityModeProperties properties;

    public MeController(IdentityService identity, IdentityModeProperties properties) {
        this.identity = identity;
        this.properties = properties;
    }

    @GetMapping("/me")
    public MeResponse me() {
        String mode = properties.accessMode().name();
        ActorContext actor = ActorContextHolder.current();
        if (actor == null) {
            return new MeResponse(mode, false, false, null, null, null, null, null,
                    List.of(), List.of());
        }
        List<MeResponse.MembershipView> memberships = actor.isUser()
                ? identity.memberships(actor.userId()).stream()
                        .map(m -> new MeResponse.MembershipView(
                                m.orgId(), m.orgName(), m.role().name()))
                        .toList()
                : List.of();
        String displayName = actor.isUser()
                ? identity.user(actor.userId()).map(IdentityService.UserView::displayName).orElse(null)
                : null;

        if (!actor.hasOrg()) {
            // 多组织用户尚未显式选择组织:如实返回,不猜一个组织。
            boolean selectionRequired = actor.isUser() && memberships.size() > 1;
            return new MeResponse(mode, true, selectionRequired, actor.userId(), displayName,
                    null, null, null, List.of(), memberships);
        }

        IdentityService.OrgView org = identity.organization(actor.orgId()).orElse(null);
        OrgRole role = actor.isUser()
                ? identity.memberships(actor.userId()).stream()
                        .filter(m -> m.orgId().equals(actor.orgId()))
                        .map(IdentityService.MembershipView::role)
                        .findFirst()
                        .orElse(null)
                : null;
        List<String> capabilities = role == null
                ? List.of()
                : role.capabilities().stream().map(Enum::name).sorted().toList();
        return new MeResponse(mode, true, false,
                actor.userId(), displayName,
                actor.orgId(), org == null ? null : org.name(),
                role == null ? null : role.name(), capabilities, memberships);
    }
}

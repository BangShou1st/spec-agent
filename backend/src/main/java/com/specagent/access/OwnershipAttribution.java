package com.specagent.access;

/**
 * 文件名:OwnershipAttribution.java
 *
 * 用途:归属主体解析的<b>单一事实源</b>(第二阶段 §2.5 的归属规则)。
 *
 * 规则只有一条,但被两类调用点共用,因此必须只有一处实现:
 * <ul>
 *   <li>HTTP 入口:主体已由 {@code ActorContextFilter} 绑定,直接用。</li>
 *   <li>进程内夹具/种子与尚未接入主体的内部调用:只在 LOCAL 模式回落到
 *       <b>显式本地主体</b>(有真实行列、可禁用、可审计),OIDC 模式下则
 *       拒绝——绝不让"没有身份"变成"按 legacy 组织行事"。</li>
 * </ul>
 *
 * 仓储层也要用它(会话创建需要落 org/owner),而仓储不应该依赖
 * {@code AuthorizationGuard}(那会形成 repository -> service 的逆向边),
 * 因此把规则提到中性类型上而不是留在守卫里。
 */
public final class OwnershipAttribution {

    private OwnershipAttribution() {
    }

    /**
     * 当前归属主体。已绑定则原样返回;未绑定且为 LOCAL 模式返回显式本地
     * 主体;其余情形抛 {@link AccessDeniedException}(失败关闭)。
     */
    public static ActorContext currentOrDefault(AccessMode mode) {
        ActorContext actor = ActorContextHolder.current();
        if (actor != null) {
            return actor;
        }
        if (mode == AccessMode.LOCAL) {
            return ActorContext.local(LocalPrincipalIds.USER_ID, LocalPrincipalIds.ORG_ID);
        }
        throw AccessDeniedException.forbidden(AccessReason.UNAUTHENTICATED,
                "An authenticated principal is required to determine resource ownership");
    }

    /**
     * 当前归属组织。未绑定主体时只有 LOCAL 模式回落到本地/存量组织。
     */
    public static java.util.UUID currentOrgOrDefault(AccessMode mode) {
        return currentOrDefault(mode).orgId();
    }
}

package com.specagent.access.api;

import java.util.List;
import java.util.UUID;

/**
 * 文件名:MeResponse.java
 *
 * 用途:{@code GET /api/me} 的线级契约。
 *
 * 刻意<b>不</b>包含:任何凭证明文/引用、内部 secret、加密键信息、
 * ContextSnapshot、模型内部类型。前端的权限字段只用于控制显示,
 * 后端绝不信任按钮状态。
 *
 * @param mode              LOCAL | OIDC
 * @param authenticated     是否存在已解析主体
 * @param orgSelectionRequired 主体有多个组织但尚未显式选择组织时为 true
 * @param userId            用户标识(未认证时为 null)
 * @param orgId             当前生效组织(未选择时为 null)
 * @param orgRole           当前组织角色
 * @param orgCapabilities   当前组织角色映射到的确定性能力集合
 * @param memberships       有效组织成员资格(供前端渲染组织切换)
 */
public record MeResponse(
        String mode,
        boolean authenticated,
        boolean orgSelectionRequired,
        UUID userId,
        String displayName,
        UUID orgId,
        String orgName,
        String orgRole,
        List<String> orgCapabilities,
        List<MembershipView> memberships) {

    /** 组织切换列表项。只暴露展示所需字段。 */
    public record MembershipView(UUID orgId, String orgName, String role) {
    }
}

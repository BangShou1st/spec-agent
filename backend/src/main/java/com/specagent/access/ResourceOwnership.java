package com.specagent.access;

import java.util.UUID;

/**
 * 文件名:ResourceOwnership.java
 *
 * 用途:一个受保护资源的归属事实。授权判定的第 4 条(资源归属)就是
 * 把它的 {@code orgId} 与 ActorContext 对照。
 *
 * 归属必须由<b>父关系链</b>推导,不接受调用方传入:例如 Answer 的归属
 * 经 answer -> node -> route -> project 得出,而不是"请求里写了 orgId"。
 *
 * @param type        资源类型
 * @param resourceId  资源标识
 * @param orgId       所属组织
 * @param ownerUserId 私有资源(如 GA 会话)的属主;共享资源为 {@code null}
 */
public record ResourceOwnership(
        AccessResourceType type,
        UUID resourceId,
        UUID orgId,
        UUID ownerUserId) {

    public boolean isPrivateTo(UUID userId) {
        return ownerUserId != null && ownerUserId.equals(userId);
    }
}

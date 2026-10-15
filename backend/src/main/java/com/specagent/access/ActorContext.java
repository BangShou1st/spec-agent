package com.specagent.access;

import java.util.UUID;

/**
 * 文件名:ActorContext.java
 *
 * 用途:一次受保护操作的主体上下文(第二阶段 §7)。
 *
 * 它必须来自<b>持久化事实</b>:用户委托任务由命令事务把 ActorContext 快照
 * 落库,恢复 Worker 再从数据库重建;绝不来自请求参数、模型输出或线程本地。
 * 请求里携带的 orgId 只是"选择条件",必须经组织成员资格校验后才成为本对象。
 *
 * @param userId 主体用户;SYSTEM/SERVICE 主体为 {@code null}
 * @param orgId  当前生效组织;SYSTEM 主体可为 {@code null}(窄服务权限),
 *               用户主体为 {@code null} 表示尚未选择组织(此时任何组织范围
 *               操作都应被拒绝,并给出"需要选择组织"的原因码)
 * @param source 来源标记
 */
public record ActorContext(UUID userId, UUID orgId, ActorSource source) {

    public ActorContext {
        if (source == null) {
            throw new IllegalArgumentException("ActorContext.source is required");
        }
        if (source.isUser() && userId == null) {
            throw new IllegalArgumentException(
                    "A user-delegated ActorContext must carry userId");
        }
    }

    public static ActorContext local(UUID userId, UUID orgId) {
        return new ActorContext(userId, orgId, ActorSource.LOCAL);
    }

    public static ActorContext oidc(UUID userId, UUID orgId) {
        return new ActorContext(userId, orgId, ActorSource.OIDC);
    }

    public static ActorContext system(UUID orgId) {
        return new ActorContext(null, orgId, ActorSource.SYSTEM);
    }

    public static ActorContext service() {
        return new ActorContext(null, null, ActorSource.SERVICE);
    }

    public boolean isUser() {
        return source.isUser();
    }

    public boolean hasOrg() {
        return orgId != null;
    }

    /** 组织选择是否缺失(用于返回"需要选择组织"而不是含糊的拒绝)。 */
    public boolean requiresOrgSelection() {
        return isUser() && orgId == null;
    }
}

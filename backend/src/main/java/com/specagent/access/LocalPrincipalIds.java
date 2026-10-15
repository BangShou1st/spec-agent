package com.specagent.access;

import java.util.UUID;

/**
 * 文件名:LocalPrincipalIds.java
 *
 * 用途:本地/存量主体与组织的固定标识。
 *
 * 为什么放在中性 access 根包而不是 identity 子包:授权判定
 * ({@link AuthorizationGuard}) 必须能在<b>没有主体</b>的进程内调用点
 * 上按归属规则回落到这个组织,而 identity 依赖 root。若常量放在 identity,
 * root 引用它就会构成 root -> identity 的反向边,把 access 内部变成环
 * (ArchUnit 的 sub-slice 无环门禁会直接失败)。
 *
 * <b>本地与存量共用同一组标识</b>:单机部署里"本地主体"就是"存量数据的
 * 主人"。分成两个组织只会让本地模式看不见自己的历史数据(行为回归),
 * 却不会带来任何隔离收益。该组值必须与 V61 迁移中的引导行逐字一致。
 */
public final class LocalPrincipalIds {

    private LocalPrincipalIds() {
    }

    /** 本地/存量组织。 */
    public static final UUID ORG_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

    /** 本地/存量用户(该组织内为 OWNER)。 */
    public static final UUID USER_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");
}

package com.specagent.access;

/**
 * 文件名:AccessResourceType.java
 *
 * 用途:需要归属解析的资源类型。每个类型都必须有一个可判定的父关系链,
 * 归属解析失败一律视为"不可见",绝不猜测。
 */
public enum AccessResourceType {
    /** 项目:归属 = projects.org_id。 */
    PROJECT,
    /** GA 会话:归属 = global_assistant_threads.org_id,属主 = owner_user_id。 */
    GA_THREAD,
    /** 画布 Run:归属与项目一致。 */
    WORKSPACE_RUN,
    /** GA Run:归属与会话一致,属主继承会话属主。 */
    GA_RUN,
    /** 规格快照:归属经 spec_snapshots.project_id 推导。 */
    SPEC_SNAPSHOT,
    /** 凭证:归属 = 凭证元数据的 org_id;USER 类型另有 owner_user_id。 */
    CREDENTIAL,
    /** 连接:归属 = connections.org_id。 */
    CONNECTION;
}

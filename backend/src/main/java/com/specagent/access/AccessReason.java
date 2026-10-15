package com.specagent.access;

/**
 * 文件名:AccessReason.java
 *
 * 用途:授权失败原因码集合与 HTTP 语义的关系(第二阶段 §6)。
 *
 * 语义契约:
 * <ul>
 *   <li>资源不可见 -> 一致的 <b>404</b>({@link #RESOURCE_NOT_VISIBLE});不泄露
 *       "存在但无权"这一事实。</li>
 *   <li>资源可见但缺少操作权限 -> <b>403</b>({@link #CAPABILITY_MISSING} 等)。</li>
 *   <li>登录失效/用户禁用 -> JSON <b>401</b>({@link #UNAUTHENTICATED}、
 *       {@link #USER_DISABLED})。</li>
 * </ul>
 */
public final class AccessReason {

    private AccessReason() {
    }

    public static final String ALLOWED = "ALLOWED";
    /** 没有已认证主体(Security 层已拒绝时不会走到这里)。 */
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    /** 主体已认证但用户被禁用——每次检查都重新生效,不依赖会话缓存。 */
    public static final String USER_DISABLED = "USER_DISABLED";
    /** 组织被禁用。 */
    public static final String ORG_DISABLED = "ORG_DISABLED";
    /** 主体尚未选择组织(多组织用户必须显式选择,不依赖会话内可变的 orgId)。 */
    public static final String ORG_SELECTION_REQUIRED = "ORG_SELECTION_REQUIRED";
    /** 请求选中的组织不在主体的有效成员资格内。 */
    public static final String NOT_ORG_MEMBER = "NOT_ORG_MEMBER";
    /** 组织成员资格被停用。 */
    public static final String MEMBERSHIP_DISABLED = "MEMBERSHIP_DISABLED";
    /** 组织角色不足以执行该动作。 */
    public static final String ORG_CAPABILITY_MISSING = "ORG_CAPABILITY_MISSING";
    /** 最后一个有效 OWNER 不允许被降级或移出组织。 */
    public static final String LAST_OWNER_PROTECTED = "LAST_OWNER_PROTECTED";
    /** 资源不存在,或存在但不在主体的可见范围内——对外一律表现为 404。 */
    public static final String RESOURCE_NOT_VISIBLE = "RESOURCE_NOT_VISIBLE";
    /** 嵌套 ID 彼此不一致(例如 route 不属于该 project)。 */
    public static final String RESOURCE_RELATION_MISMATCH = "RESOURCE_RELATION_MISMATCH";
    /** 项目角色不足以执行该动作。 */
    public static final String PROJECT_CAPABILITY_MISSING = "PROJECT_CAPABILITY_MISSING";
    /** 会话不是主体的私有会话。 */
    public static final String THREAD_NOT_OWNED = "THREAD_NOT_OWNED";
    /** 凭证不可用(缺失、停用、跨组织)——绝不回退匿名。 */
    public static final String CREDENTIAL_NOT_USABLE = "CREDENTIAL_NOT_USABLE";
    /** 系统派生主体越出已登记来源/产物范围。 */
    public static final String SYSTEM_SCOPE_EXCEEDED = "SYSTEM_SCOPE_EXCEEDED";
    /**
     * 受信任服务主体(内部 token 已通过)试图直接使用业务授权。
     * 内部 token 只说明"请求来自受信任服务",绝不单独构成业务授权;
     * 运行中的 RPC 还必须验证 task token 与 ActorContext。
     */
    public static final String SERVICE_NOT_AUTHORIZED = "SERVICE_NOT_AUTHORIZED";
    /** 部署模式与数据库登记不一致。 */
    public static final String MODE_MISMATCH = "MODE_MISMATCH";
    /** 授权范围行缺失——授权事实不可确认时失败关闭。 */
    public static final String AUTHORIZATION_SCOPE_UNKNOWN = "AUTHORIZATION_SCOPE_UNKNOWN";
}

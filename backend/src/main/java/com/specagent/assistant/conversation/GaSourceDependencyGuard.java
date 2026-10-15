package com.specagent.assistant.conversation;

import com.specagent.access.AccessDeniedException;
import com.specagent.access.ActorContext;
import com.specagent.access.ActorSource;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 文件名:GaSourceDependencyGuard.java
 *
 * 用途:GA 派生记忆(会话消息、摘要、working state、checkpoint、公开事件)的
 * <b>项目来源依赖</b>授权判定,失败关闭。
 *
 * <p>为什么需要它:GA 会话默认私有,而"私有"今天只由属主资格表达。用户失去项目
 * 角色后,属主资格与组织成员资格都还有效,于是旧会话里引用该项目正文的消息预览、
 * 消息详情、事件来源卡片与 checkpoint 仍可读取,下一轮执行还会把旧观察装配进
 * 模型历史。派生记忆必须叠加"其依赖的项目对主体仍可读"这一条,否则撤权无从判定
 * 要隔离哪条派生记忆。
 *
 * <p><b>依赖账本来自既有持久化事实,没有新表、没有迁移</b>:
 * <ul>
 *   <li>{@code global_assistant_run_events} 里 {@code TOOL_COMPLETED} 的
 *       {@code payload.resourceRefs} 携带 Host 投影的<b>结构化资源对象</b>
 *       ({@code GlobalAssistantToolPresentation.projectResources} 产出
 *       {@code {"kind":"PROJECT","id":<uuid>,"label":…,"metadata":…}},与
 *       {@code SOURCE}/{@code WEB_SOURCE} 卡片同列一个数组)。
 *       <b>注意不要把它和回给 Python 的 broker {@code sourceRefs} 混为一谈</b>:
 *       后者是 {@code "project:<uuid>"} 字符串({@code GaCapabilityBroker}),
 *       而事件载荷里是对象。判定按 {@code kind=PROJECT} 取 {@code id};字符串形态
 *       仍被容忍,这样历史行与两种形态都能被同一谓词覆盖;</li>
 *   <li>{@code UI_ACTION} 的 {@code payload.resourceId} 承载 {@code ui.navigate}
 *       导航到的项目 id——该分支绕过 capability invocation,没有
 *       {@code capability_invocations} 行,因此必须从事件里取。</li>
 * </ul>
 * 选事件而不是 {@code capability_invocations}:后者没有 {@code run_id} 索引
 * (V13 只索引了 invocation_key 与 project_id),而按 thread→runs→invocations
 * 重建依赖会对一张全库增长表做顺序扫描,列表接口每线程一次,不可接受。
 *
 * <p><b>完整性不变量</b>(由 {@code GaSourceDependencyInvariantTest} 钉死):
 * 项目粒度的引用不会被展示截断——{@code projectResources} 上限 10,而
 * {@code project.search}/{@code project.list_recent} 的 limit 上限恰为 10,
 * {@code project.content.discover} 候选上限 4,{@code create}/{@code get_summary}
 * 各 1。若该不变量被破坏(某项项目引用可被截断),本账本即不可靠,必须停下来
 * 改为显式依赖持久化,而不是继续在这个判定上加补丁。
 *
 * <p><b>失败关闭的边界</b>(区分"确知无依赖"与"依赖未知"):
 * <ul>
 *   <li>项目范围能力的 {@code TOOL_COMPLETED} 缺 {@code resourceRefs} 键,或该键
 *       不是数组 → 依赖未知 → 整会话隔离;</li>
 *   <li>分类集合之外的 capabilityId → 依赖未知 → 整会话隔离(新增工具必须显式
 *       登记,不能靠"没写引用"默认放行);</li>
 *   <li>{@code user-input.request}、{@code ui.navigate} 等确知无项目依赖的能力
 *       绝不能因此被误判,否则所有会话都会被封锁。</li>
 * </ul>
 *
 * <p><b>判定语义</b>:线程被隔离 ⟺ 其任一 run 的任一事件表明依赖了主体当前
 * <b>不可读</b>的项目,或依赖无法确认。可读性的项目维度与
 * {@code AuthorizationGuard.projectDecisionFor(..., PROJECT_READ)} 一致:
 * 同组织且({@code LOCAL} 主体 | 拥有项目角色)。用户/组织启用状态与成员资格由
 * 同一请求的属主判定负责,这里不重复。
 *
 * <p><b>锁与并发</b>:纯读判定,不加任何锁——不取项目行锁、不取 authorization
 * scope 锁(因此不改变 {@code authorization scope → business Run → checkpoint}
 * 的既有锁顺序)。读侧是快照语义:撤权提交后,新读请求看到的是撤权后的事实;
 * 与之并发的读可能看到提交前的事实,这与既有属主/成员判定同构,已由执行侧的
 * 授权围栏(RetrievalGrantAuthorization/TaskWriteGuard/GA fence)覆盖 in-flight
 * 的执行路径。
 */
@Service
public class GaSourceDependencyGuard {

    /**
     * 结果必然携带项目依赖的能力(结果形状见 {@code GlobalAssistantToolPresentation}).
     */
    public static final List<String> PROJECT_SCOPED_CAPABILITIES = List.of(
            "project.create", "project.search", "project.list_recent",
            "project.get_summary", "project.content.discover");

    /**
     * 确知不含项目依赖的能力:{@code ui.navigate} 走的项目依赖在 {@code UI_ACTION}
     * 事件里,{@code user-input.request} 只回一个澄清问题。
     */
    public static final List<String> NON_PROJECT_CAPABILITIES = List.of(
            "help.search", "web.search", "web.fetch", "skill.import",
            "skill.import.discover", "user-input.request", "ui.navigate");

    public static final Set<String> CLASSIFIED_CAPABILITIES;

    static {
        Set<String> classified = new LinkedHashSet<>(PROJECT_SCOPED_CAPABILITIES);
        classified.addAll(NON_PROJECT_CAPABILITIES);
        CLASSIFIED_CAPABILITIES = Set.copyOf(classified);
    }

    private static final String PROJECT_SCOPED_SQL = sqlLiterals(PROJECT_SCOPED_CAPABILITIES);
    private static final String CLASSIFIED_SQL = sqlLiterals(List.copyOf(CLASSIFIED_CAPABILITIES));

    private final NamedParameterJdbcTemplate jdbc;

    public GaSourceDependencyGuard(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 判定并抛异常:依赖已撤权或无法确认时,按"会话不存在"处理(与私有会话的
     * 拒绝同构,不区分"不存在"与"依赖已失效")。
     */
    public void requireSourcesReadable(ActorContext actor, UUID threadId) {
        if (threadId == null || isThreadIsolated(actor, threadId)) {
            throw AccessDeniedException.notVisible("Conversation not found");
        }
    }

    /** 纯判定:线程是否必须被隔离(依赖已撤权 / 依赖未知 / 主体不可判定)。 */
    public boolean isThreadIsolated(ActorContext actor, UUID threadId) {
        if (actor == null || actor.orgId() == null || actor.userId() == null) {
            // 没有可判定的主体就没有授权:绝不因为"不知道是谁"而放行。
            return true;
        }
        Map<String, Object> params = new HashMap<>();
        String predicate = isolationPredicate("CAST(:threadId AS uuid)", actor, params);
        params.put("threadId", threadId.toString());
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT " + predicate, params, Boolean.class));
    }

    /**
     * 会话列表(候选生成阶段)用的谓词:{@code AND NOT <predicate>}。语义与
     * {@link #isThreadIsolated} 完全相同——同一条 SQL,不另写一套过滤逻辑;
     * 放在 {@code LIMIT} 之前,数量与排序因此不会泄露被隔离会话。
     *
     * @param threadIdExpression 外层线程 id 的列表达式(例如 {@code t.id})
     */
    public String notIsolatedClause(String threadIdExpression, ActorContext actor,
                                    Map<String, Object> parameters) {
        return " AND NOT " + isolationPredicate(threadIdExpression, actor, parameters);
    }

    /**
     * 被隔离谓词体。{@code threadIdExpression} 是线程 id 表达式:{@code :threadId}
     * 走命名参数(单线程判定),{@code t.id} 关联外层查询(列表候选生成)。
     */
    private String isolationPredicate(String threadIdExpression, ActorContext actor,
                                      Map<String, Object> parameters) {
        // 参数按文本绑定并由 SQL 显式 CAST 成 uuid:驱动把未知类型参数发送为 varchar,
        // 而 uuid 列对 varchar 没有隐式转换,因此比较两侧必须显式对齐类型。
        parameters.put("orgId", actor.orgId().toString());
        parameters.put("userId", actor.userId().toString());
        parameters.put("unrestricted", actor.source() == ActorSource.LOCAL);
        return """
                EXISTS (
                  SELECT 1
                  FROM global_assistant_runs r
                  JOIN global_assistant_run_events e ON e.run_id = r.id
                  WHERE r.thread_id = %THREAD%
                    AND (
                      (e.type = 'TOOL_COMPLETED' AND e.payload->>'capabilityId' IN (%PROJECT_SCOPED%)
                         AND NOT jsonb_exists(e.payload, 'resourceRefs'))
                      OR (e.type = 'TOOL_COMPLETED' AND jsonb_exists(e.payload, 'resourceRefs')
                         AND jsonb_typeof(e.payload->'resourceRefs') <> 'array')
                      OR (e.type = 'TOOL_COMPLETED'
                         AND COALESCE(e.payload->>'capabilityId', '') NOT IN (%CLASSIFIED%))
                      OR (e.type = 'TOOL_COMPLETED'
                         AND jsonb_typeof(e.payload->'resourceRefs') = 'array'
                         AND EXISTS (
                             SELECT 1
                             FROM jsonb_array_elements(e.payload->'resourceRefs') AS ref
                             JOIN projects p ON p.id = CASE
                                 -- 真实 broker 投影:结构化资源对象,按 kind=PROJECT 取 id。
                                 WHEN jsonb_typeof(ref) = 'object' AND ref->>'kind' = 'PROJECT'
                                      AND (ref->>'id') ~ '^[0-9a-fA-F-]{36}$'
                                 THEN (ref->>'id')::uuid
                                 -- 兼容形态:字符串 "project:<uuid>"(broker sourceRefs 风格)。
                                 WHEN jsonb_typeof(ref) = 'string'
                                      AND starts_with(ref #>> '{}', 'project:')
                                      AND substring(ref #>> '{}' from 9) ~ '^[0-9a-fA-F-]{36}$'
                                 THEN substring(ref #>> '{}' from 9)::uuid END
                             WHERE p.org_id <> CAST(:orgId AS uuid)
                                OR (NOT CAST(:unrestricted AS boolean) AND NOT EXISTS (
                                     SELECT 1 FROM project_members pm
                                     WHERE pm.project_id = p.id
                                       AND pm.user_id = CAST(:userId AS uuid)))
                         ))
                      OR (e.type = 'UI_ACTION' AND e.payload->>'destination' = 'PROJECT'
                         AND EXISTS (
                             SELECT 1 FROM projects p
                             WHERE p.id = CASE
                                 WHEN (e.payload->>'resourceId') ~ '^[0-9a-fA-F-]{36}$'
                                 THEN (e.payload->>'resourceId')::uuid END
                               AND (p.org_id <> CAST(:orgId AS uuid)
                                  OR (NOT CAST(:unrestricted AS boolean) AND NOT EXISTS (
                                       SELECT 1 FROM project_members pm
                                       WHERE pm.project_id = p.id
                                         AND pm.user_id = CAST(:userId AS uuid))))
                         ))
                    )
                )
                """
                .replace("%THREAD%", threadIdExpression)
                .replace("%PROJECT_SCOPED%", PROJECT_SCOPED_SQL)
                .replace("%CLASSIFIED%", CLASSIFIED_SQL);
    }

    /** 由编译期常量拼出的 IN 列表字面量,不接受外部输入。 */
    private static String sqlLiterals(List<String> values) {
        return values.stream().map(value -> "'" + value + "'")
                .collect(java.util.stream.Collectors.joining(", "));
    }
}

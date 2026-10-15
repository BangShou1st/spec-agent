package com.specagent.assistant.conversation;

import com.specagent.access.ActorContext;
import com.specagent.access.IdentityModeProperties;
import com.specagent.access.OwnershipAttribution;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 文件名:GlobalAssistantThreadListRepository.java
 *
 * 用途:会话列表(Conversation Library)读模型的查询层:用一条
 * 有界 SQL 一次取出最近线程的标题源文本与预览源文本,无 N+1。
 *
 * 角色:conversation 包的列表查询层。只列出至少含一条 USER 消息的
 * 线程;排序按最新规范消息 created_at 倒序,并以线程 id 倒序打破平局,
 * 保证结果确定。标题/预览的截断交给 {@link GlobalAssistantConversationLibrary}。
 *
 * <b>第二阶段(身份/权限)</b>:列表必须按<b>组织 + 属主</b>过滤。
 * 原实现没有任何 owner/org 谓词,返回全库最近会话的标题与预览
 * (预览取自最后一条消息正文),经 {@code GET /api/v1/global-assistant/threads}
 * 直接暴露(P2-A 清单 G2)。它也是设计 §9"缓存的助手记忆不能继续泄露
 * 项目内容"最直接的入口:会话列表本身就是一条派生记忆。
 *
 * 过滤放在 SQL 内而不是取回后在内存里筛:数量与排序本身就是信息——
 * 先取全库再过滤,仍会通过分页与排序泄露"存在大量他人会话"这一事实。
 * 归属取自创建时写入的 {@code org_id}/{@code owner_user_id};归属为 NULL
 * 的行不会被任何主体匹配到(失败关闭)。
 */
@Repository
public class GlobalAssistantThreadListRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final IdentityModeProperties properties;
    private final GaSourceDependencyGuard dependencies;

    public GlobalAssistantThreadListRepository(NamedParameterJdbcTemplate jdbc,
                                               IdentityModeProperties properties,
                                               GaSourceDependencyGuard dependencies) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.dependencies = dependencies;
    }

    public List<GlobalAssistantThreadListItem> listRecent(int limit) {
        int bounded = Math.max(1, Math.min(limit, GlobalAssistantConversationLibrary.LIST_LIMIT));
        ActorContext owner = OwnershipAttribution.currentOrDefault(properties.accessMode());
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        // uuid 列对 varchar 没有隐式转换,驱动按文本发送未知类型参数,因此比较两侧显式对齐类型。
        params.put("orgId", owner.orgId().toString());
        params.put("userId", owner.userId().toString());
        // 候选生成阶段即排除"依赖已撤权 / 依赖未知"的会话:谓词是 NOT EXISTS,
        // 作用在 LIMIT 之前,因此被隔离会话既不出现,也不占用分页名额。
        String isolationFilter = dependencies.notIsolatedClause("t.id", owner, params);
        String sql = """
                SELECT t.id AS thread_id, t.created_at AS thread_created,
                       first_msg.content AS title_src,
                       last_msg.content AS preview_src,
                       last_msg.created_at AS last_created
                FROM global_assistant_threads t
                JOIN (
                    SELECT thread_id, content
                    FROM (
                        SELECT thread_id, content,
                               ROW_NUMBER() OVER (PARTITION BY thread_id ORDER BY sequence ASC) AS rn
                        FROM global_assistant_messages
                        WHERE role = 'USER'
                    ) ranked WHERE rn = 1
                ) first_msg ON first_msg.thread_id = t.id
                JOIN (
                    SELECT thread_id, content, created_at
                    FROM (
                        SELECT thread_id, content, created_at,
                               ROW_NUMBER() OVER (PARTITION BY thread_id ORDER BY sequence DESC) AS rn
                        FROM global_assistant_messages
                    ) ranked WHERE rn = 1
                ) last_msg ON last_msg.thread_id = t.id
                WHERE t.org_id = CAST(:orgId AS uuid) AND t.owner_user_id = CAST(:userId AS uuid)
                """ + isolationFilter + """
                ORDER BY last_msg.created_at DESC, t.id DESC
                LIMIT %d
                """.formatted(bounded);
        return jdbc.query(sql, params, (rs, rowNum) -> {
            UUID threadId = rs.getObject("thread_id", UUID.class);
            Instant threadCreated = rs.getTimestamp("thread_created").toInstant();
            String titleSrc = rs.getString("title_src");
            String previewSrc = rs.getString("preview_src");
            Timestamp lastTs = rs.getTimestamp("last_created");
            Instant updatedAt = lastTs == null ? threadCreated : lastTs.toInstant();
            return new GlobalAssistantThreadListItem(
                    threadId,
                    GlobalAssistantConversationLibrary.toTitle(titleSrc),
                    GlobalAssistantConversationLibrary.toPreview(previewSrc),
                    updatedAt,
                    threadCreated);
        });
    }
}

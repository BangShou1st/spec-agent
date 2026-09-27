package com.specagent.assistant.conversation;

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
 */
@Repository
public class GlobalAssistantThreadListRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public GlobalAssistantThreadListRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<GlobalAssistantThreadListItem> listRecent(int limit) {
        int bounded = Math.max(1, Math.min(limit, GlobalAssistantConversationLibrary.LIST_LIMIT));
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
                ORDER BY last_msg.created_at DESC, t.id DESC
                LIMIT %d
                """.formatted(bounded);
        return jdbc.getJdbcOperations().query(sql, (rs, rowNum) -> {
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

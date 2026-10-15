package com.specagent.access;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 文件名:AccessAuditRecorder.java
 *
 * 用途:安全审计写入(第二阶段 §4.1)。
 *
 * <b>绝不记录业务正文、凭证明文或密钥</b>:只写 actor / org / action /
 * resource / outcome / reason / correlationId。
 *
 * 两种语义,调用方必须区分:
 * <ul>
 *   <li>{@link #record} 抛出异常——用于<b>管理动作</b>(授予项目角色、
 *       改成员、停用凭证)。它们与授权事实更新同事务,审计失败必须让
 *       整个管理动作失败,否则会出现"提了权但没有审计"。合同条款式要求
 *       "授予行为须审计",就是靠这一点落地。</li>
 *   <li>{@link #recordQuietly} 尽力而为——用于判定拒绝路径。拒绝发生时
 *       数据库可能正不可用,此时把 403 变成 500 只会掩盖真实原因;
 *       失败降级为告警日志。</li>
 * </ul>
 */
@Repository
public class AccessAuditRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(AccessAuditRecorder.class);

    private final NamedParameterJdbcTemplate jdbc;

    public AccessAuditRecorder(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UUID actorUserId, UUID orgId, String action, String resourceType,
                       String resourceId, String outcome, String reasonCode,
                       String correlationId) {
        // 用允许 null 的 LinkedHashMap:actorUserId / orgId / resourceId 在
        // 匿名拒绝与系统主体路径上都是 null,而 Map.ofEntries 对 null 值抛 NPE。
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("actorUserId", actorUserId);
        params.put("orgId", orgId);
        params.put("action", action);
        params.put("resourceType", resourceType);
        params.put("resourceId", resourceId);
        params.put("outcome", outcome);
        params.put("reasonCode", reasonCode == null ? "" : reasonCode);
        params.put("correlationId", correlationId == null ? "" : correlationId);
        jdbc.update("""
                INSERT INTO security_audit_events
                    (actor_user_id, org_id, action, resource_type, resource_id,
                     outcome, reason_code, correlation_id)
                VALUES
                    (:actorUserId, :orgId, :action, :resourceType, :resourceId,
                     :outcome, :reasonCode, :correlationId)
                """, params);
    }

    public void recordQuietly(UUID actorUserId, UUID orgId, String action, String resourceType,
                              String resourceId, String outcome, String reasonCode) {
        try {
            record(actorUserId, orgId, action, resourceType, resourceId, outcome,
                    reasonCode, null);
        } catch (RuntimeException ex) {
            LOG.warn("Security audit write failed for action={} outcome={} reason={}",
                    action, outcome, reasonCode);
        }
    }
}

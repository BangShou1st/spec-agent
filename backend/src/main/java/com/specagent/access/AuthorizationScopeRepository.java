package com.specagent.access;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 文件名:AuthorizationScopeRepository.java
 *
 * 用途:授权范围的取锁与版本(第二阶段 §8)。
 *
 * 所有取锁方法都<b>要求调用方已在事务中</b>:行锁只在事务内有效,自动提交
 * 下 {@code FOR SHARE}/{@code FOR UPDATE} 会在语句结束时释放,保护不了任何
 * 东西(P1 已在事件追加路径上踩过同一个坑)。
 *
 * 锁顺序协议:{@link #lockShared} 与 {@link #lockExclusive} 都在 SQL 内
 * {@code ORDER BY scope_key},保证多把锁按升序取得。调用方必须传入
 * {@link AuthorizationScopes#sorted} 的结果,不得自行拼序。
 *
 * 与执行范围锁的关系(不可颠倒):
 * <pre>
 *   task -> 容量槽 -> execution scope -> authorization scope -> 业务 Run -> 业务行
 * </pre>
 * 因此授权锁必须在<b>取得任务/执行范围之后</b>才获取,且在业务行写入之前。
 */
@Repository
public class AuthorizationScopeRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AuthorizationScopeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 幂等预建范围行。授权事实不能因为"范围行还没建"而无法加锁——
     * 那会让撤权与提交的先后失去串行化点。
     */
    public void ensure(Collection<String> scopeKeys) {
        List<String> keys = AuthorizationScopes.sorted(scopeKeys);
        if (keys.isEmpty()) {
            return;
        }
        Map<String, Object>[] batch = new Map[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            batch[i] = Map.of("key", keys.get(i));
        }
        jdbc.batchUpdate("""
                INSERT INTO authorization_scopes (scope_key, version)
                VALUES (:key, 0)
                ON CONFLICT (scope_key) DO NOTHING
                """, batch);
    }

    /**
     * 取共享锁并按升序读取。执行事务用它:并发撤权(排他)与提交(共享)
     * 因此有确定的提交顺序——先拿到共享锁的短事务可以先提交,撤权随后完成;
     * 撤权先提交则后续检查读到新事实并被拒绝。
     *
     * @return 实际锁住的范围键(升序);调用方必须核对其数量与请求一致,
     *         缺行意味着授权事实不可确认,应失败关闭
     */
    public List<String> lockShared(Collection<String> scopeKeys) {
        return lock(scopeKeys, "FOR SHARE");
    }

    /**
     * 取排他锁。撤权事务用它,并且<b>只</b>锁授权范围与授权事实——
     * 绝不在持锁时取消 task(那会形成 authorization -> task 与其他
     * 提交链相反方向的锁环)。撤权提交后写持久化取消请求,由控制面
     * 按 task 锁顺序处理。
     */
    public List<String> lockExclusive(Collection<String> scopeKeys) {
        return lock(scopeKeys, "FOR UPDATE");
    }

    private List<String> lock(Collection<String> scopeKeys, String mode) {
        List<String> keys = AuthorizationScopes.sorted(scopeKeys);
        if (keys.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT scope_key FROM authorization_scopes
                WHERE scope_key IN (:keys)
                ORDER BY scope_key
                """ + mode, Map.of("keys", keys),
                (rs, rowNum) -> rs.getString("scope_key"));
    }

    /** 授权版本。缓存键与失效标记都以它为准,不以本地 TTL 为准。 */
    public Optional<Long> version(String scopeKey) {
        return jdbc.query("""
                SELECT version FROM authorization_scopes WHERE scope_key = :key
                """, Map.of("key", scopeKey),
                (rs, rowNum) -> rs.getLong("version")).stream().findFirst();
    }

    public Map<String, Long> versions(Collection<String> scopeKeys) {
        List<String> keys = AuthorizationScopes.sorted(scopeKeys);
        Map<String, Long> result = new LinkedHashMap<>();
        if (keys.isEmpty()) {
            return result;
        }
        // 用 queryForList 而不是带 lambda 的 query:后者的 lambda 会同时匹配
        // RowCallbackHandler 与 ResultSetExtractor,重载解析选到 extractor 后
        // 不会自行推进游标,表现为 "invalid cursor position"。
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT scope_key, version FROM authorization_scopes
                WHERE scope_key IN (:keys)
                ORDER BY scope_key
                """, Map.of("keys", keys));
        for (Map<String, Object> row : rows) {
            Object version = row.get("version");
            result.put((String) row.get("scope_key"),
                    version instanceof Number number ? number.longValue() : null);
        }
        return result;
    }

    /**
     * 递增版本——撤权提交点。必须与授权事实更新在<b>同一事务</b>,
     * 否则会出现"事实已变但版本未变"的窗口,缓存失效标记会漏发。
     *
     * 递增前先补建范围行:撤权必须能在版本上留下痕迹。若范围行缺失,
     * 一条 {@code UPDATE ... WHERE scope_key IN (...)} 会静默更新 0 行,
     * 版本不变、缓存不失效——这正是"撤权了但旧缓存继续回显"的成因。
     */
    public void bumpVersions(Collection<String> scopeKeys) {
        List<String> keys = AuthorizationScopes.sorted(scopeKeys);
        if (keys.isEmpty()) {
            return;
        }
        ensure(keys);
        jdbc.update("""
                UPDATE authorization_scopes
                SET version = version + 1, updated_at = now()
                WHERE scope_key IN (:keys)
                """, Map.of("keys", keys));
    }
}

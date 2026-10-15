package com.specagent.access;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * 文件名:AuthorizationScopes.java
 *
 * 用途:授权范围键的构造与排序规则(第二阶段 §8)。
 *
 * 键族与 {@code runtime_task_scopes} 分表,语义不重叠:
 * <pre>
 *   authz:org:{orgId}
 *   authz:project:{projectId}
 *   authz:thread:{threadId}
 *   authz:credential:{credentialId}
 *   authz:user:{userId}
 * </pre>
 *
 * <b>排序是死锁避免协议的一部分</b>:任何需要多把授权锁的事务,都必须以
 * {@code scope_key} 升序取锁,与 P1 执行范围锁的规则同构。{@link #sorted}
 * 是唯一的排序入口,调用方不得自行拼接后乱序取锁。
 */
public final class AuthorizationScopes {

    private static final String ORG = "authz:org:";
    private static final String PROJECT = "authz:project:";
    private static final String THREAD = "authz:thread:";
    private static final String CREDENTIAL = "authz:credential:";
    private static final String USER = "authz:user:";

    private AuthorizationScopes() {
    }

    public static String org(UUID orgId) {
        return ORG + require(orgId, "orgId");
    }

    public static String project(UUID projectId) {
        return PROJECT + require(projectId, "projectId");
    }

    public static String thread(UUID threadId) {
        return THREAD + require(threadId, "threadId");
    }

    public static String credential(UUID credentialId) {
        return CREDENTIAL + require(credentialId, "credentialId");
    }

    public static String user(UUID userId) {
        return USER + require(userId, "userId");
    }

    /**
     * 去重并按字典序升序排列——取锁顺序的唯一权威形式。
     */
    public static List<String> sorted(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String key : keys) {
            if (key != null && !key.isBlank()) {
                distinct.add(key);
            }
        }
        List<String> result = new ArrayList<>(distinct);
        result.sort(String::compareTo);
        return List.copyOf(result);
    }

    /** 撤权事务影响的整个用户:{@code authz:user:*} 与其全部 {@code authz:org:*}。 */
    public static List<String> forUser(UUID userId, Collection<UUID> orgIds) {
        List<String> keys = new ArrayList<>();
        keys.add(user(userId));
        if (orgIds != null) {
            for (UUID orgId : orgIds) {
                if (orgId != null) {
                    keys.add(org(orgId));
                }
            }
        }
        return sorted(keys);
    }

    private static UUID require(UUID value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("AuthorizationScopes." + name + " is required");
        }
        return value;
    }
}

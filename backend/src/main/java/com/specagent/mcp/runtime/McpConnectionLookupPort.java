package com.specagent.mcp.runtime;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:McpConnectionLookupPort.java
 *
 * 用途:MCP 运行时持有的窄连接查询端口(seam):解析协议层所需的
 * {@link McpConnectionTarget} 投影。由连接侧实现该接口,因此 {@code com.specagent.mcp}
 * 永远不会 import {@code com.specagent.connection},连接存储保持唯一事实来源。
 */
public interface McpConnectionLookupPort {

    /** 返回当前主体可见的连接投影;组织/个人过滤必须在候选 SQL 内完成。 */
    List<McpConnectionTarget> list();

    /** 按内部行 id 查找。 */
    Optional<McpConnectionTarget> findByRowId(UUID rowId);

    /** Result-source checks hold ownership and connection locks to transaction completion. */
    default Optional<McpConnectionTarget> lockVisibleResultSource(UUID rowId) {
        throw new com.specagent.mcp.transport.McpTransportException("MCP_RESULT_SOURCE_GUARD_UNAVAILABLE");
    }

    /** 按对外的产品级连接 id({@code conn_...})查找。 */
    Optional<McpConnectionTarget> findByConnectionId(String connectionId);
}

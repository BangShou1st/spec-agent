package com.specagent.capability;

import java.util.List;

/**
 * 文件名:McpAdapter.java
 *
 * 用途:MCP Server 适配器的边界接口。一个 MCP Server 可能暴露工具(tools)、
 * 资源(resources)和提示(prompts);适配器对它们做有意的映射:
 *
 * - MCP 工具 → 可调用的能力,附带副作用元数据;
 * - MCP 资源 → 可检索的资源上下文,附带溯源信息;
 * - MCP 提示 → 可复用的提示资产,绝不作为系统策略的自动覆盖。
 *
 * 连接、凭据、权限、上下文暴露和用户审批都由应用宿主持有。本阶段尚未接入
 * 任何 MCP 适配器;一旦接入,必须通过 {@link #exposedPrimitiveKinds()} 暴露其
 * Server 支持的原始类型,以便注册表对它们分类。
 */
public interface McpAdapter extends CapabilityAdapter {

    enum PrimitiveKind { TOOL, RESOURCE, PROMPT }

    /** 该适配器映射了哪些 MCP 原始类型。 */
    List<PrimitiveKind> exposedPrimitiveKinds();
}

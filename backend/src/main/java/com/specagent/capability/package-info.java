/**
 * 文件名:package-info.java
 *
 * Capability 基础设施:在 Agent 的 {@code INVOKE_CAPABILITY} 动作与具体实现
 * (内置工具、Skill 包、MCP Server)之间提供通用适配器边界。
 *
 * 描述符由运行时持有,在暴露给模型前经过权限过滤;规划器永远看不到实现类。
 * 能力结果是保留溯源的观测,进入后续有界的决策周期——永远不会被自动确认为
 * 图谱事实。运行时通过持久化的调用日志持有重试/幂等元数据。
 */
package com.specagent.capability;

/**
 * 文件名:package-info.java
 *
 * 用途:com.specagent.agent.trace 包说明——模型调用 trace 记录,
 * 为调试与回放保存元数据。
 *
 * 内容:存储 provider 名称、模型 id、prompt 版本、ContextSnapshot id、
 * AgentRun id、请求/响应哈希与耗时信息。
 *
 * 约束:不存储任何密钥(secrets)。
 */
package com.specagent.agent.trace;
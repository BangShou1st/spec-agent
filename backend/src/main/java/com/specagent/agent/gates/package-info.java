/**
 * 文件名:package-info.java
 *
 * 针对已校验模型输出的反思与 grounding 门禁包。
 *
 * 门禁是"模型提出"与"Runtime 执行"之间的 fail-closed 检查点:
 * 上下文新鲜度({@code ContextGuard})、patch 反思({@code PatchReflectionGate})、
 * spec grounding({@code SpecGroundingGate})与来源引用完整性
 * ({@code SpecSourceReferenceGuard})。它们读取 Runtime 事实(包括仓库),
 * 但从不调用模型或决策引擎。
 */
package com.specagent.agent.gates;

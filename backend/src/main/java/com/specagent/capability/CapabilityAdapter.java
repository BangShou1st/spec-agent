package com.specagent.capability;

/**
 * 文件名:CapabilityAdapter.java
 *
 * 用途:所有 Capability 适配器实现的端口(Port)接口。适配器把一个有界的能力契约
 * 翻译成具体实现(内置 Java 工具、Skill 包、MCP Server 或未来的提供方),
 * 使实现细节不会泄漏到规划器(Planner)和动作协议中。
 */
public interface CapabilityAdapter {

    /** 该适配器实现的有界契约描述。 */
    CapabilityDescriptor descriptor();

    /**
     * 执行该能力。实现要求:相同参数下必须具有确定性;除非描述符的
     * side-effect class 声明允许,否则绝不能修改外部系统;对于预期内的失败
     * 应返回带类型的结果,而不是抛异常。
     */
    CapabilityResult invoke(CapabilityInvocation invocation);
}

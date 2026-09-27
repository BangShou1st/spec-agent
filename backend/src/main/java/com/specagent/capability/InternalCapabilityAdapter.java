package com.specagent.capability;

/**
 * 文件名:InternalCapabilityAdapter.java
 *
 * 用途:标记在 Java 运行时内部实现的能力(内置工具)。内部适配器可以
 * 通过仓储读取运行时状态,但不持有任何凭据,也永远接触不到模型/网关内部组件。
 */
public interface InternalCapabilityAdapter extends CapabilityAdapter {
}

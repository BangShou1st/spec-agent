package com.specagent.capability;

import java.util.Collection;
import java.util.Optional;

/**
 * 文件名:CapabilityProvider.java
 *
 * 用途:动态能力提供方的 SPI。一个 Provider 拥有一族能力,其描述符可以随时间
 * 变化(例如从某个连接动态发现的 MCP 工具),而规划器核心无需任何改动。
 *
 * Provider 绝不向规划器暴露实现类、SDK 客户端、凭据或端点:{@link CapabilityDescriptor}
 * 是唯一对模型可见的契约。可用性由 Provider 自己负责——只有当底层能力确实可用时
 * (如连接已启用/已连接),Provider 才返回(或解析出)描述符,因此被禁用的资源
 * 在构造上就会从规划器候选集中消失。
 *
 * 一个 Server 可能拥有多个工具;一个 Provider 并不等于一个能力。多个 Provider
 * 之间(或与静态适配器之间)出现重复的 capability id 时,注册表会按"失败即关闭"
 * (fail closed)处理。
 */
public interface CapabilityProvider {

    /** 用于诊断/追踪的稳定 Provider 名称;永远不对规划器暴露。 */
    String providerName();

    /**
     * 返回当前查询上下文下可提供的描述符。Provider 自行做可用性过滤
     * (启用/已连接/已配置);权限过滤由注册表在其结果之上执行。
     */
    Collection<CapabilityDescriptor> descriptorsFor(CapabilityQueryContext context);

    /** 当该 id 归此 Provider 所有时返回 true(指所有权,不代表可用)。 */
    boolean canHandle(String capabilityId);

    /**
     * 返回某个 id 在当前可用时的描述符;能力被禁用/断开/未知时返回空。
     * 策略层在调用时用它来解析副作用分类。
     */
    Optional<CapabilityDescriptor> descriptorFor(String capabilityId);

    /**
     * 执行该能力。调用方必须先通过针对描述符副作用分类的策略检查;
     * 幂等/重试由 {@link CapabilityRuntime} 负责,而不是 Provider。
     */
    CapabilityResult invoke(CapabilityInvocation invocation);
}
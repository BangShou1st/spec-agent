package com.specagent.runtime.execution;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 文件名:RuntimeExecutionConfig.java
 *
 * 用途:公共调度层的装配。执行身份是进程级单例(每次启动新 UUID),
 * 配置项绑定 {@code spec.agent.execution.*}。
 *
 * <p>本包只依赖公共基础设施和自己定义的端口,绝不直接引用画布/助手业务
 * 服务(design §10);业务侧通过实现 {@link TaskExecutionAdapter} 等端口接入,
 * 保持既有 ArchUnit 无环约束。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RuntimeExecutionSettings.class)
public class RuntimeExecutionConfig {

    /** 本进程的执行身份;每次启动都是新的 UUID(design §5.1)。 */
    @Bean
    public WorkerIdentity workerIdentity() {
        return new WorkerIdentity();
    }
}

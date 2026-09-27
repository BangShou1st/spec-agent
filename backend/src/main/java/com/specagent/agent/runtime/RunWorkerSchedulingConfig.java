package com.specagent.agent.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 文件名:RunWorkerSchedulingConfig.java
 *
 * 用途:仅在 worker 被显式开启时启用后台轮询调度。切换完成后的默认
 * profile 通过 application.yml 打开它,保证任何接收 agent run 的进程都
 * 一定带有执行器。测试与纯 API 部署仍可通过
 * {@code SPEC_AGENT_BRAIN_WORKER_ENABLED=false} 关闭;此时健康端点会报告
 * 缺少执行器,而不是让 run 在队列里无声滞留。
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "spec.agent.brain.worker.enabled", havingValue = "true")
public class RunWorkerSchedulingConfig {
}

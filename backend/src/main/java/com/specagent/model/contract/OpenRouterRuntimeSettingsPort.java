package com.specagent.model.contract;

/**
 * 文件名:OpenRouterRuntimeSettingsPort.java
 *
 * 用途:OpenRouter 推理网关读取运行时设置的窄接口。只在配置存在且通过了当前
 * 版本校验时才返回配置,否则一律按失败处理(fail closed),语义等价于历史上的
 * requireStored + requireActivatable 两步检查。由设置侧实现。
 */
public interface OpenRouterRuntimeSettingsPort {

    /** 返回可激活的 OpenRouter 设置;配置不可用时抛出 NOT_CONFIGURED 类失败。 */
    RuntimeOpenRouterSettings requireRuntimeSettings();
}

package com.specagent.model.contract;

/**
 * 文件名:CustomRuntimeSettingsPort.java
 *
 * 用途:自定义提供商推理网关读取运行时设置的窄接口。只在配置存在且通过了当前版本
 * 校验时才返回配置,否则一律按失败处理(fail closed),语义等价于历史上的
 * requireStored + requireActivatable 两步检查。API 格式必须是显式存储的值,
 * 绝不做自动探测。由设置侧实现。
 */
public interface CustomRuntimeSettingsPort {

    /** 返回可激活的自定义设置;配置不可用时抛出 NOT_CONFIGURED 类失败。 */
    RuntimeCustomSettings requireRuntimeSettings();
}

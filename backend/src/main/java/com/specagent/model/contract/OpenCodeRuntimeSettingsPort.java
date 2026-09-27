package com.specagent.model.contract;

/**
 * 文件名:OpenCodeRuntimeSettingsPort.java
 *
 * 用途:OpenCode 推理网关读取运行时设置的窄接口:返回已解析的凭据和所选模型,
 * 未配置时按失败处理(fail closed)。由设置侧实现。
 */
public interface OpenCodeRuntimeSettingsPort {

    /** 返回已解析的运行时设置;未配置时抛出 {@link com.specagent.model.provider.OpenCodeModelException} 的 NOT_CONFIGURED。 */
    RuntimeOpenCodeSettings requireRuntimeSettings();
}

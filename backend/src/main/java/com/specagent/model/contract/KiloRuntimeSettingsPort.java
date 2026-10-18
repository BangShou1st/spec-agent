package com.specagent.model.contract;

/**
 * 文件名:KiloRuntimeSettingsPort.java
 *
 * 用途:Kilo Gateway 推理侧解析运行时设置的窄接口(端口)。由模型推理侧定义、
 * 设置侧实现,这样推理链路无需依赖 {@code com.specagent.modelsettings} 包;实现方
 * 必须在内部自行解析凭据,失败即拒绝(fail closed)。
 */
public interface KiloRuntimeSettingsPort {

    /** 返回当前可用于推理的 Kilo 运行时设置;未配置时抛出终止性异常。 */
    RuntimeKiloSettings requireRuntimeSettings();
}

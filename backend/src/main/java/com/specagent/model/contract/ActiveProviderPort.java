package com.specagent.model.contract;

import com.specagent.model.contract.ModelProvider;

/**
 * 文件名:ActiveProviderPort.java
 *
 * 用途:路由网关查询"当前激活的是哪个模型提供商"的窄接口(端口)。由模型推理侧定义、
 * 设置侧实现,这样推理链路无需依赖 {@code com.specagent.modelsettings} 包。
 */
public interface ActiveProviderPort {

    /** 返回当前激活的提供商;未存储任何配置时默认 OPENCODE_ZEN。 */
    ModelProvider activeProvider();
}

package com.specagent.model.provider;

import java.util.Map;
import java.util.Optional;

/**
 * 文件名:OpenCodeZenModelMetadataSource.java
 *
 * 用途:OpenCode 官方模型目录元数据的读取边界(协议 / 价格 / 下线状态)。
 *
 * <p>这是一条<b>可失败</b>的外部事实来源,因此契约显式区分两种"没有答案":
 * <ul>
 *   <li>{@link View#available()} 为 false:元数据本次不可用(网络、超时、报文不合法)。
 *       调用方必须退回保守规则,<b>绝不</b>假设"没有价格就是免费";</li>
 *   <li>{@code available()} 为 true 但 {@code find(id)} 为空:元数据可用、但该模型未被
 *       登记——属于"依赖未知",不得被当作已验证可执行的模型。</li>
 * </ul>
 */
public interface OpenCodeZenModelMetadataSource {

    View view();

    record View(boolean available, Map<String, OpenCodeZenModelMetadata> models) {

        public View {
            models = models == null ? Map.of() : Map.copyOf(models);
        }

        public static View unavailable() {
            return new View(false, Map.of());
        }

        public Optional<OpenCodeZenModelMetadata> find(String modelId) {
            return modelId == null ? Optional.empty() : Optional.ofNullable(models.get(modelId));
        }
    }
}

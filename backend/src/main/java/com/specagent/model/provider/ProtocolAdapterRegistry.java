package com.specagent.model.provider;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 文件名:ProtocolAdapterRegistry.java
 *
 * 用途:协议路由的唯一边界。Agent 代码绝不在 {@link CustomApiFormat} 上做
 * 分支判断,只需向本注册表查询一次即可拿到对应的协议适配器。启动时校验所有
 * 格式都已注册,防止运行期出现缺适配器的隐患。
 */
@Component
public class ProtocolAdapterRegistry {

    private final Map<CustomApiFormat, ProtocolAdapter> adapters;

    public ProtocolAdapterRegistry(List<ProtocolAdapter> all) {
        Map<CustomApiFormat, ProtocolAdapter> map = new EnumMap<>(CustomApiFormat.class);
        for (ProtocolAdapter adapter : all) {
            map.put(adapter.format(), adapter);
        }
        if (!map.keySet().containsAll(List.of(CustomApiFormat.values()))) {
            throw new IllegalStateException("Missing protocol adapter registration");
        }
        this.adapters = Map.copyOf(map);
    }

    public ProtocolAdapter require(CustomApiFormat format) {
        ProtocolAdapter adapter = adapters.get(format);
        if (adapter == null) {
            throw new IllegalArgumentException("Unsupported api format: " + format);
        }
        return adapter;
    }
}

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

    /**
     * 当前<b>已缓存</b>的可信视图,绝不触发刷新。运行期协议门禁(它们运行在
     * 持有业务 Run 锁的准备事务内)必须用本方法读取,从而绝不在锁内发起网络请求。
     *
     * <p>默认实现退回 {@link #view()}:对无网络副作用的确定性实现(单测 stub)而言
     * 两者等价;真正持有远端来源的实现必须覆写它以只返回缓存快照。
     *
     * @see OpenCodeZenMetadataCatalog#cachedView()
     */
    default View cachedView() {
        return view();
    }

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

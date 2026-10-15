package com.specagent.model.provider;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文件名:OpenCodeModelCatalog.java
 *
 * 用途:OpenCode Zen 的动态模型发现与模型资格判定。模型 id 从实时的
 * {@code GET /models} 载荷中发现,绝不来自硬编码的生产清单;免费与协议判定来自
 * 官方模型目录元数据({@link OpenCodeZenModelMetadataSource})。
 *
 * <p><b>为什么不能只看 id 后缀</b>:线上目录里 {@code big-pickle} 的价格元数据是
 * input=0/output=0(免费),却没有 {@code -free} 后缀;而
 * {@code muse-spark-1.3-contributor-free} 虽是免费,但 {@code provider.npm} 是
 * {@code @ai-sdk/openai},走 Responses 端点。因此:
 * <ul>
 *   <li>免费 ⟺ 元数据明确 input=0 且 output=0 且未下线。带 {@code -free} 名字的付费
 *       模型永远不算免费;缺 {@code -free} 后缀的免费模型同样会被发现;</li>
 *   <li>可执行 ⟺ 协议已验证为 Chat Completions(本产品只实现这条调用链),
 *       元数据里未登记的模型属于"未验证",不进可选列表;</li>
 *   <li>元数据不可用时退回已验证的保守规则(仅 {@code -free} 后缀;协议无法判定,
 *       保持既有可用列表),<b>绝不</b>把"没有价格"当成免费。</li>
 * </ul>
 */
@Component
public class OpenCodeModelCatalog {

    private final OpenCodeZenTransport transport;
    private final OpenCodeZenModelMetadataSource metadata;

    @Autowired
    public OpenCodeModelCatalog(OpenCodeZenTransport transport, OpenCodeZenModelMetadataSource metadata) {
        this.transport = transport;
        this.metadata = metadata;
    }

    /**
     * 没有元数据来源的变体:免费判定退回 {@code -free} 后缀规则,协议无法判定。
     * 供不装配完整上下文的使用方(eval harness、确定性单测)使用。
     */
    public OpenCodeModelCatalog(OpenCodeZenTransport transport) {
        this(transport, null);
    }

    /**
     * 返回提供商当前暴露的全部模型 id(免费 + 付费),排序后用于稳定展示。
     * <b>这不是选择列表</b>:协议未验证的模型也在其中,资格判定见
     * {@link #listAvailableModels}。
     *
     * @param apiKey 可选的 bearer 凭据;模型发现是公开接口,可为 null 或空白
     */
    public List<String> listAllModels(String apiKey) {
        return transport.listModels(apiKey).data().stream()
                .map(OpenCodeModel::id)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 可选择的模型:提供商暴露且原生协议已验证为 Chat Completions 的模型。
     * Responses / Anthropic / 其它协议模型<i>不会</i>出现在这里——Spec Agent 只实现
     * Chat 调用链,把它们显示成可选模型必然在请求时失败。
     */
    public List<String> listAvailableModels(String apiKey) {
        return listAllModels(apiKey).stream().filter(this::chatExecutable).toList();
    }

    /**
     * 免费模型:可执行且被元数据明确标记为免费。
     */
    public List<String> listFreeModels(String apiKey) {
        return listAllModels(apiKey).stream()
                .filter(this::chatExecutable)
                .filter(this::isFreeModel)
                .toList();
    }

    /** 该模型的原生协议;元数据不可用或未登记时为 {@link OpenCodeModelProtocol#UNKNOWN}。 */
    public OpenCodeModelProtocol protocolOf(String modelId) {
        if (metadata == null) {
            return OpenCodeModelProtocol.UNKNOWN;
        }
        return metadata.view().find(modelId).map(OpenCodeZenModelMetadata::protocol)
                .orElse(OpenCodeModelProtocol.UNKNOWN);
    }

    /**
     * 协议是否已验证可执行。元数据可用时"未登记"即未验证,不进选择列表;
     * 元数据不可用时无法判定,退回既有可用列表(不新增误判)。
     */
    public boolean chatExecutable(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        if (metadata == null) {
            return true;
        }
        OpenCodeZenModelMetadataSource.View view = metadata.view();
        if (!view.available()) {
            return true;
        }
        return view.find(modelId).map(OpenCodeZenModelMetadata::chatExecutable).orElse(false);
    }

    /**
     * <b>运行期协议门禁(失败关闭)</b>:Spec Agent 的 GA 执行链只实现
     * {@code POST /chat/completions},因此只有经可信元数据确认协议为
     * {@link OpenCodeModelProtocol#CHAT} 的模型才允许进入执行信封。
     *
     * <p>与 {@link #chatExecutable(String)} 的关键区别:后者是<i>目录展示</i>的资格
     * 判定,元数据不可用时退回既有可用列表以不误伤正在工作的模型;本方法用于
     * <i>运行期执行门禁</i>,只读<b>已缓存</b>的可信元数据(绝不触发网络刷新,因而
     * 可以安全地运行在持有 Run 锁的准备事务内),并且:
     * <ul>
     *   <li>元数据不可用(冷启动/刷新失败)→ 无法确认 → {@code false};</li>
     *   <li>模型未登记或已下线 → {@code false};</li>
     *   <li>协议为 RESPONSES / ANTHROPIC / OTHER / UNKNOWN → {@code false}。</li>
     * </ul>
     * 绝不把"无法确认"当成 Chat,从而旧配置里保存的异协议模型不可能被发到
     * {@code /chat/completions}。
     */
    public boolean verifiedChatExecutable(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        if (metadata == null) {
            return false;
        }
        OpenCodeZenModelMetadataSource.View view = metadata.cachedView();
        if (!view.available()) {
            return false;
        }
        return view.find(modelId).map(OpenCodeZenModelMetadata::chatExecutable).orElse(false);
    }

    /**
     * 预热/读取可信元数据视图(允许触发 TTL 刷新,单飞且失败同样按 TTL 节流)。
     * <b>只允许在业务事务与 Run 锁之外调用</b>:GA 分发入口在进入
     * {@code GaExecutionPreparation}(它持有 Run 锁并在事务内运行)之前调用它,
     * 使运行期门禁随后能纯内存判定协议——绝不为协议门禁引入持有 Run 锁的网络请求。
     */
    public void warmMetadata() {
        if (metadata != null) {
            metadata.view();
        }
    }

    /**
     * 免费判定。元数据可用:必须由价格元数据证明(input=0 且 output=0,且未下线),
     * 名字里带 {@code -free} 的付费模型不算免费;未登记的模型不算免费。
     * 元数据不可用:退回 {@code -free} 后缀(既有规则),"没有价格"绝不等于免费。
     */
    public boolean isFreeModel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        if (metadata == null) {
            return modelId.endsWith("-free");
        }
        OpenCodeZenModelMetadataSource.View view = metadata.view();
        if (!view.available()) {
            return modelId.endsWith("-free");
        }
        return view.find(modelId)
                .map(metadata -> metadata.free() && !metadata.retired())
                .orElse(false);
    }
}

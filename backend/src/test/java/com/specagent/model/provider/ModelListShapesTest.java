package com.specagent.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 文件名:ModelListShapesTest.java
 *
 * 测试目标:验证模型列表响应的解析形态:去重、排序、忽略缺失 id 的条目;
 * 报文形态非法时抛出 ModelProviderException;HTTP 错误映射上 401/403/429/5xx
 * 仍为硬错误,绝不能降级为手动兜底路径。
 */
class ModelListShapesTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatCompletionsProtocolAdapter adapter = new ChatCompletionsProtocolAdapter();

    @Test void dedupeSortingBounded() throws Exception {
        var root = mapper.readTree("{\"data\":[{\"id\":\"b\"},{\"id\":\"a\"},{\"id\":\"b\"},{}]}");
        assertThat(adapter.parseModelList(root, "c")).containsExactly("a", "b");
    }

    @Test void malformedThrows() {
        assertThatThrownBy(() -> adapter.parseModelList(mapper.createObjectNode(), "c"))
                .isInstanceOf(ModelProviderException.class);
    }

    @Test void httpErrorDistinguishesManualFromFailure() {
        // 404/405/501 在 HTTP 层按手动兜底处理(此处不抛异常);
        // 401/403/429/5xx 必须保持硬错误,绝不能降级为手动。
        assertThat(adapter.mapHttpError(401, "", "c").gatewayCategory().name()).isEqualTo("AUTHENTICATION");
        assertThat(adapter.mapHttpError(403, "", "c").gatewayCategory().name()).isEqualTo("AUTHENTICATION");
        assertThat(adapter.mapHttpError(429, "", "c").gatewayCategory().name()).isEqualTo("RATE_LIMITED");
        assertThat(adapter.mapHttpError(500, "", "c").gatewayCategory().name()).isEqualTo("SERVER_ERROR");
    }
}

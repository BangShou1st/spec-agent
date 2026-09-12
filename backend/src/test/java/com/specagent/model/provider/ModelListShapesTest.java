package com.specagent.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

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
        // 404/405/501 are handled as manual fallback at the HTTP layer (no throw there);
        // 401/403/429/5xx must stay hard errors and never degrade to manual.
        assertThat(adapter.mapHttpError(401, "", "c").gatewayCategory().name()).isEqualTo("AUTHENTICATION");
        assertThat(adapter.mapHttpError(403, "", "c").gatewayCategory().name()).isEqualTo("AUTHENTICATION");
        assertThat(adapter.mapHttpError(429, "", "c").gatewayCategory().name()).isEqualTo("RATE_LIMITED");
        assertThat(adapter.mapHttpError(500, "", "c").gatewayCategory().name()).isEqualTo("SERVER_ERROR");
    }
}

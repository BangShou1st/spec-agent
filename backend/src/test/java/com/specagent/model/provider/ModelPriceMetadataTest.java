package com.specagent.model.provider;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
class ModelPriceMetadataTest {
    @Test void namesAndMissingPricesNeverProveFree() throws Exception {
        var root=new ObjectMapper().readTree("""
                {"data":[{"id":"misleading:free"},{"id":"free-word","pricing":{"prompt":"0"}},
                {"id":"zero","pricing":{"prompt":"0","completion":"0.000"}},
                {"id":"paid","pricing":{"prompt":"0","completion":"1"}},
                {"id":"other-cost","pricing":{"prompt":"0","completion":"0","request":"1"}},
                {"id":"unknown","pricing":{"prompt":"unknown","completion":"0"}}]}
                """);
        assertThat(ModelPriceMetadata.explicitlyFreeIds(root,List.of("misleading:free","free-word",
                "zero","paid","other-cost","unknown"))).containsExactly("zero");
    }
}

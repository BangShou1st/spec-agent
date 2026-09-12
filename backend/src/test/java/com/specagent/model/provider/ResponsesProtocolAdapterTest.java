package com.specagent.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.inference.ModelInferenceMessage;
import com.specagent.model.inference.ModelInferenceRequest;
import com.specagent.model.inference.ModelOutputContract;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ResponsesProtocolAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ResponsesProtocolAdapter adapter = new ResponsesProtocolAdapter();

    @Test void doesNotCopyResponseFormat() {
        var req = new ModelInferenceRequest(UUID.randomUUID(), "p",
                List.of(new ModelInferenceMessage("user", "hi")), 256, ModelOutputContract.jsonObject());
        var body = adapter.buildRequestBody(req, "m");
        assertThat(body).doesNotContainKey("response_format");
        assertThat(body).containsKey("text");
        Map<?, ?> text = (Map<?, ?>) body.get("text");
        Map<?, ?> fmt = (Map<?, ?>) text.get("format");
        assertThat(fmt.get("type")).isEqualTo("json_object");
    }

    @Test void normalExtraction() throws Exception {
        var root = mapper.readTree("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}]}");
        assertThat(adapter.parseNonStreamResponse(root, "r").content()).isEqualTo("hello");
    }

    @Test void onlyOutputTextDeltaSurfaces() throws Exception {
        var good = mapper.readTree("{\"type\":\"response.output_text.delta\",\"delta\":\"Hi\"}");
        assertThat(adapter.extractVisibleText(good, "r")).isEqualTo("Hi");
        var reasoning = mapper.readTree("{\"type\":\"response.reasoning.delta\",\"delta\":\"secret\"}");
        assertThat(adapter.extractVisibleText(reasoning, "r")).isNull();
        // Substring delta without exact event type must not surface.
        var fake = mapper.readTree("{\"type\":\"other\",\"delta\":\"Hi\"}");
        assertThat(adapter.extractVisibleText(fake, "r")).isNull();
    }

    @Test void completedIsTerminal() throws Exception {
        var done = mapper.readTree("{\"type\":\"response.completed\"}");
        assertThat(adapter.isTerminalData("{}", done, "r")).isTrue();
        var delta = mapper.readTree("{\"type\":\"response.output_text.delta\",\"delta\":\"x\"}");
        assertThat(adapter.isTerminalData("{}", delta, "r")).isFalse();
    }

    @Test void nonStreamOnlyCompletedPasses() throws Exception {
        var completed = mapper.readTree("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}]}");
        assertThat(adapter.parseNonStreamResponse(completed, "r").content()).isEqualTo("hello");
    }

    @Test void nonStreamFailureStatusesFailEvenWithOutput() throws Exception {
        for (String status : new String[]{"incomplete", "failed", "cancelled", "in_progress", "queued"}) {
            var root = mapper.readTree("{\"status\":\"" + status + "\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"looks-valid\"}]}]}");
            assertThatThrownBy(() -> adapter.parseNonStreamResponse(root, "r"))
                    .isInstanceOf(ModelProviderException.class);
        }
    }

    @Test void nonStreamTopLevelErrorFails() throws Exception {
        var root = mapper.readTree("{\"status\":\"completed\",\"error\":{\"message\":\"boom\"},\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}]}");
        assertThatThrownBy(() -> adapter.parseNonStreamResponse(root, "r"))
                .isInstanceOf(ModelProviderException.class);
    }

    @Test void nonStreamMissingStatusFailsClosed() throws Exception {
        var root = mapper.readTree("{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}]}");
        assertThatThrownBy(() -> adapter.parseNonStreamResponse(root, "r"))
                .isInstanceOf(ModelProviderException.class);
    }

    @Test void nonStreamIncompleteDetailsFails() throws Exception {
        var root = mapper.readTree("{\"status\":\"completed\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}]}");
        assertThatThrownBy(() -> adapter.parseNonStreamResponse(root, "r"))
                .isInstanceOf(ModelProviderException.class);
    }
}

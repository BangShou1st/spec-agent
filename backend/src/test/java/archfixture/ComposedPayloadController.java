package archfixture;

import com.specagent.model.contract.ModelOutputContract;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/** Payload composition fixtures; none of the child types is referenced by an endpoint directly. */
public class ComposedPayloadController {

    // Controller implementation dependencies are not HTTP payloads.
    private final InternalRequest internal = new InternalRequest(null);

    @GetMapping("/fixture/composed")
    public ResponseEntity<OuterResponse> get() {
        return ResponseEntity.ok(new OuterResponse(List.of(), Map.of(), null));
    }

    @PostMapping("/fixture/composed")
    public void create(@RequestBody OuterRequest request) {
    }

    @GetMapping("/fixture/view")
    public ReadView view() {
        return new ReadView("read-model");
    }

    @GetMapping("/fixture/generic")
    public Envelope<GenericDetails> generic() {
        return new Envelope<>(null);
    }

    @GetMapping("/fixture/getter")
    public GetterResponse getter() {
        return new GetterResponse();
    }

    private InternalRequest helper() {
        return internal;
    }

    public record OuterResponse(List<InnerResponse> children,
                                Map<String, ? extends Envelope<Details[]>> details,
                                OuterResponse next) { }

    public record InnerResponse(ModelOutputContract contract) { }

    // No DTO suffix: payload coverage must not depend on the child's name.
    public record Details(ModelOutputContract contract) { }

    public record GenericDetails(ModelOutputContract contract) { }

    public record Envelope<T>(T value) { }

    public record OuterRequest(ChildRequest child) { }

    public record ChildRequest(String value) { }

    public record ReadView(String value) { }

    public record InternalRequest(ModelOutputContract contract) { }

    public static class GetterResponse {
        public GetterDetails getDetails() {
            return null;
        }
    }

    public record GetterDetails(ModelOutputContract contract) { }
}

package archfixture;

import com.specagent.model.contract.ModelOutputContract;

/**
 * VIOLATION fixture for {@code httpSurfaceMustNotReferenceModelInternals}:
 * an HTTP-only DTO (Response-suffixed, controller-referenced) that carries a
 * model-internal type.
 */
public class SampleLeakyResponse {

    private final ModelOutputContract contract;

    public SampleLeakyResponse(ModelOutputContract contract) {
        this.contract = contract;
    }
}

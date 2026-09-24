package archfixture;

/** A core service must not depend on a request buried inside another HTTP request. */
public class ComposedPayloadCoreService {
    private final ComposedPayloadController.ChildRequest request;

    public ComposedPayloadCoreService(ComposedPayloadController.ChildRequest request) {
        this.request = request;
    }
}

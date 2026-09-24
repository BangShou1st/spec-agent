package archfixture;

/**
 * VIOLATION fixture for {@code coreMustNotDependOnHttpOnlyDtos}: a core
 * service holding a reference to an HTTP-only DTO.
 */
public class SampleCoreService {

    private final SampleCreateProjectRequest leakedRequest;

    public SampleCoreService(SampleCreateProjectRequest leakedRequest) {
        this.leakedRequest = leakedRequest;
    }
}
